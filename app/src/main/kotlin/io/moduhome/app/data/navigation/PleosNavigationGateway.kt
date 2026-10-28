package io.moduhome.app.data.navigation

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.RoutePointType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import io.moduhome.app.core.navigation.NavigationConnection
import io.moduhome.app.core.navigation.NavigationGateway
import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.navigation.NavigationSnapshot
import io.moduhome.app.core.navigation.ObservedNavigationPlace
import io.moduhome.app.core.navigation.PlaceCatalogResult
import io.moduhome.app.core.navigation.RouteRequestResult
import io.moduhome.app.core.navigation.TripEstimate
import io.moduhome.app.core.navigation.TripEstimateResult
import io.moduhome.app.core.navigation.matchesTripDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** SDK calls and listener registration are confined to the main dispatcher. */
internal class PleosNavigationGateway(context: Context) : NavigationGateway {
    private val context = context.applicationContext
    private val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val mutableConnection = MutableStateFlow<NavigationConnection>(
        if (supported) NavigationConnection.Connecting else NavigationConnection.Unsupported,
    )
    override val connection = mutableConnection.asStateFlow()
    private val operation = Mutex()
    private val listeners = mutableSetOf<NaviHelperEventListener>()
    // TripInfo has no request ID or source timestamp. Do not issue the same destination twice
    // on one helper connection: an old callback could otherwise confirm the newer request.
    // A callback initiated elsewhere for this exact POI cannot be distinguished by this SDK.
    private val requestedTripDestinations = mutableListOf<NavigationPoint>()
    private var tripResponseUncertain = false
    private var helper: NaviHelper? = null
    @Volatile private var closed = false

    override fun connect() {
        if (!supported || closed || helper != null) return
        try {
            val sdk = NaviHelper(context)
            helper = sdk
            sdk.initialize(
                onConnected = { if (!closed) mutableConnection.value = NavigationConnection.Ready },
                onFailed = {
                    if (!closed) mutableConnection.value = NavigationConnection.Failed("차량 내비를 실행한 뒤 다시 연결해주세요")
                },
            )
        } catch (_: Exception) {
            mutableConnection.value = NavigationConnection.Failed("차량 내비에 연결할 수 없어요")
        }
    }

    override fun close() {
        closed = true
        val sdk = helper ?: return
        listeners.toList().forEach(sdk::removeListener)
        listeners.clear()
        helper = null
        sdk.release()
    }

    override suspend fun requestRoute(plan: NavigationPlan): RouteRequestResult = withContext(Dispatchers.Main.immediate) {
        val sdk = readyHelper() ?: return@withContext RouteRequestResult.Unavailable
        if (!operation.tryLock()) return@withContext RouteRequestResult.Rejected("내비 응답을 확인하고 있어요. 잠시 뒤 다시 시도해주세요")
        var lastSnapshot: NavigationSnapshot? = null
        try {
            val existing = readSnapshot(sdk)
            if (existing?.confirms(plan) == true) {
                val confirmed = readSnapshot(sdk)
                if (confirmed?.confirms(plan) == true) return@withContext RouteRequestResult.Confirmed(confirmed)
            }
            val failure = CompletableDeferred<NaviErrorCode>()
            val listener = object : NaviHelperEventListener {
                override fun onError(errorCode: NaviErrorCode) { failure.complete(errorCode) }
            }
            register(sdk, listener)
            try {
                sdk.requestRouteWithWaypoints(plan.toSdkRequest())
                withTimeoutOrNull(35_000) {
                    var matchingReads = 0
                    do {
                        if (failure.isCompleted) {
                            return@withTimeoutOrNull RouteRequestResult.Rejected(failure.await().userMessage(), lastSnapshot)
                        }
                        val snapshot = readSnapshot(sdk)
                        if (snapshot != null) lastSnapshot = snapshot
                        matchingReads = if (snapshot?.confirms(plan) == true) matchingReads + 1 else 0
                        if (matchingReads < 2) delay(500)
                    } while (matchingReads < 2)
                    RouteRequestResult.Confirmed(checkNotNull(lastSnapshot))
                } ?: RouteRequestResult.Rejected(
                    "차량 내비에서 목적지와 경유 순서를 확인하지 못했어요. 내비 화면을 확인한 뒤 다시 시도해주세요",
                    lastSnapshot,
                )
            } finally {
                unregister(sdk, listener)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            RouteRequestResult.Rejected("차량 내비 접근 권한을 확인해주세요", lastSnapshot)
        } catch (_: Exception) {
            RouteRequestResult.Rejected("차량 내비 요청을 처리하지 못했어요", lastSnapshot)
        } finally {
            operation.unlock()
        }
    }

    override suspend fun currentRoute(): NavigationSnapshot? = withContext(Dispatchers.Main.immediate) {
        operation.withLock {
            val sdk = readyHelper() ?: return@withLock null
            try {
                readSnapshot(sdk)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun loadPlaces(): PlaceCatalogResult = withContext(Dispatchers.Main.immediate) {
        operation.withLock {
            val sdk = readyHelper() ?: return@withLock PlaceCatalogResult.Failed("차량 내비 연결을 먼저 확인해주세요")
            val bookmarks = CompletableDeferred<List<NavigationPoint>>()
            val recent = CompletableDeferred<List<NavigationPoint>>()
            val listener = object : NaviHelperEventListener {
                override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
                    bookmarks.complete(bookmarkInfoList.mapNotNull { it.toPoint() })
                }
                override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
                    recent.complete(recentDestinationInfoList.mapNotNull { it.toPoint() })
                }
            }
            register(sdk, listener)
            try {
                sdk.getBookmarkInfo()
                sdk.getRecentDestinationInfo()
                withTimeoutOrNull(6_000) {
                    PlaceCatalogResult.Loaded(bookmarks.await(), recent.await())
                } ?: PlaceCatalogResult.Failed("장소 목록을 받지 못했어요. 차량 내비를 실행한 뒤 새로고침해주세요")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PlaceCatalogResult.Failed("내비의 저장 장소와 최근 목적지를 불러오지 못했어요")
            } finally {
                unregister(sdk, listener)
            }
        }
    }

    override suspend fun estimateTrip(destination: NavigationPoint): TripEstimateResult = withContext(Dispatchers.Main.immediate) {
        if (!supported) return@withContext TripEstimateResult.Unsupported
        val sdk = readyHelper() ?: return@withContext TripEstimateResult.Failed("차량 내비 연결을 먼저 확인해주세요")
        if (!operation.tryLock()) {
            return@withContext TripEstimateResult.Failed("내비 응답을 확인하고 있어요. 잠시 뒤 다시 조회해주세요")
        }
        var requested = false
        var received = false
        try {
            if (tripResponseUncertain || requestedTripDestinations.any { it.sameTripDestination(destination) }) {
                return@withContext TripEstimateResult.Failed("새 이동시간을 확인하려면 차량 내비를 다시 연결해주세요")
            }
            val before = readSnapshot(sdk)
                ?: return@withContext TripEstimateResult.Failed("현재 차량 내비 상태를 확인하지 못했어요")
            if (readyHelper() !== sdk) return@withContext TripEstimateResult.Failed("차량 내비 연결이 끊겼어요")
            val response = CompletableDeferred<TripInfo>()
            val failure = CompletableDeferred<NaviErrorCode>()
            val routeChanged = CompletableDeferred<Unit>()
            val armed = AtomicBoolean(false)
            val listener = object : NaviHelperEventListener {
                override fun onTripInfo(tripInfo: TripInfo) {
                    if (armed.get() && tripInfo.destinationObservation()?.matchesTripDestination(destination) == true) {
                        response.complete(tripInfo)
                    }
                }

                override fun onError(errorCode: NaviErrorCode) {
                    if (armed.get() && errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) failure.complete(errorCode)
                }

                override fun onRouteStarted(routeStartInfo: RouteStartInfo) { changed() }
                override fun onRouteEnded() { changed() }
                override fun onRouteCancelled() { changed() }
                override fun onDestinationChanged() { changed() }
                override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { changed() }

                private fun changed() { if (armed.get()) routeChanged.complete(Unit) }
            }
            register(sdk, listener)
            try {
                val queriedAt = System.currentTimeMillis()
                requestedTripDestinations += destination
                requested = true
                armed.set(true)
                sdk.requestTrip(TripRequest(
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                    poiId = destination.placeId,
                    poiName = destination.name,
                    poiSubId = destination.subId.takeIf(String::isNotEmpty),
                ))
                val info = withTimeoutOrNull(12_000) {
                    select<TripInfo?> {
                        response.onAwait { it }
                        failure.onAwait { null }
                        routeChanged.onAwait { null }
                    }
                }
                if (info == null) {
                    return@withContext TripEstimateResult.Failed(
                        when {
                            routeChanged.isCompleted -> "조회 중 차량 내비 경로가 바뀌었어요. 현재 안내를 확인해주세요"
                            failure.isCompleted -> failure.await().userMessage()
                            else -> "차량 내비에서 이동시간을 받지 못했어요. 내비를 다시 연결한 뒤 조회해주세요"
                        },
                    )
                }
                received = true
                val after = readSnapshot(sdk)
                if (readyHelper() !== sdk) return@withContext TripEstimateResult.Failed("차량 내비 연결이 끊겼어요")
                if (routeChanged.isCompleted) {
                    return@withContext TripEstimateResult.Failed("조회 중 차량 내비 경로가 바뀌었어요. 현재 안내를 확인해주세요")
                }
                return@withContext TripEstimate.fromObservation(
                    destination = destination,
                    observedDestination = info.destinationObservation(),
                    durationSeconds = info.eta,
                    distanceMeters = info.distance,
                    queriedAtEpochMillis = queriedAt,
                    observedAtEpochMillis = System.currentTimeMillis(),
                    routeBefore = before,
                    routeAfter = after,
                )
            } finally {
                armed.set(false)
                unregister(sdk, listener)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            TripEstimateResult.Failed("차량 내비 접근 권한을 확인해주세요")
        } catch (_: Exception) {
            TripEstimateResult.Failed("차량 내비의 이동시간을 조회하지 못했어요")
        } finally {
            // A timed-out/cancelled request can still complete in the service after our listener
            // is removed. Fail closed until reconnect rather than attach that reply to a retry.
            if (requested && !received) tripResponseUncertain = true
            operation.unlock()
        }
    }

    private fun readyHelper(): NaviHelper? =
        helper?.takeIf { !closed && mutableConnection.value == NavigationConnection.Ready }

    private fun register(sdk: NaviHelper, listener: NaviHelperEventListener) {
        sdk.addListener(listener)
        listeners += listener
    }

    private fun unregister(sdk: NaviHelper, listener: NaviHelperEventListener) {
        if (listeners.remove(listener) && helper === sdk && !closed) sdk.removeListener(listener)
    }

    private suspend fun readSnapshot(sdk: NaviHelper): NavigationSnapshot? {
        val destination = CompletableDeferred<ObservedNavigationPlace?>()
        val waypoints = CompletableDeferred<List<ObservedNavigationPlace>>()
        val guidance = CompletableDeferred<Boolean>()
        val listener = object : NaviHelperEventListener {
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                destination.complete(observedPlace(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude,
                    destinationInfo.poiId, destinationInfo.poiSubId, destinationInfo.poiProvider))
            }
            override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                // The returned list is the vehicle's order, so it must not be sorted here.
                waypoints.complete(waypointInfoList.mapNotNull {
                    observedPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId, it.poiProvider)
                })
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                guidance.complete(routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING ||
                    routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING)
            }
        }
        register(sdk, listener)
        return try {
            sdk.getDestinationInfo()
            sdk.getWaypointInfo()
            sdk.getRouteStateInfo()
            withTimeoutOrNull(2_000) {
                NavigationSnapshot(destination.await(), waypoints.await(), guidance.await())
            }
        } finally {
            unregister(sdk, listener)
        }
    }
}

private fun NavigationPlan.toSdkRequest() = RouteWithWaypointsRequest(
    destination = RouteInfo(
        latitude = destination.latitude, longitude = destination.longitude,
        displayLatitude = null, displayLongitude = null, poiName = destination.name,
        poiId = destination.placeId, address = destination.address, poiSubId = destination.subId,
        poiProvider = destination.provider,
    ),
    waypoints = waypoints.mapIndexed { index, point ->
        RequestWaypointInfo(
            latitude = point.latitude, longitude = point.longitude, poiName = point.name,
            waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
            poiId = point.placeId, address = point.address, poiSubId = point.subId, poiProvider = point.provider,
        )
    },
)

private fun point(
    name: String?, latitude: Double, longitude: Double, address: String?, id: String?, subId: String?, provider: String?,
): NavigationPoint? {
    if (name.isNullOrBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return NavigationPoint(name, latitude, longitude, address.orEmpty(), id.orEmpty(), subId.orEmpty(), provider.orEmpty())
}

private fun observedPlace(
    name: String?, latitude: Double, longitude: Double, id: String?, subId: String?, provider: String?,
): ObservedNavigationPlace? {
    if (name.isNullOrBlank()) return null
    // Pleos AVD reports unavailable waypoint coordinates as 0,0; keep them absent.
    val hasCoordinates = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)
    return ObservedNavigationPlace(name, id.orEmpty(), provider.orEmpty(),
        latitude.takeIf { hasCoordinates }, longitude.takeIf { hasCoordinates }, subId.orEmpty())
}

private fun BookmarkInfo.toPoint() = point(name, latitude, longitude, address, poiId, poiSubId, poiProvider)
private fun RecentDestinationInfo.toPoint() = point(name, latitude, longitude, address, poiId, poiSubId, poiProvider)

private fun NavigationPoint.sameTripDestination(other: NavigationPoint): Boolean =
    // TripInfo does not include a provider or entrance sub-ID, so neither may distinguish retries.
    ObservedNavigationPlace(name, placeId, latitude = latitude, longitude = longitude)
        .matches(other.copy(provider = "", subId = ""))

private fun TripInfo.destinationObservation(): ObservedNavigationPlace? {
    val destination = summary.routePoints.singleOrNull { it.pointType == RoutePointType.DESTINATION } ?: return null
    return observedPlace(destination.poiName, destination.latitude, destination.longitude, destination.poiId, null, null)
}

private fun NaviErrorCode.userMessage(): String = when (this) {
    NaviErrorCode.CAUSE_LOW_BATTERY -> "차량 내비에서 배터리 부족으로 경로를 설정하지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "차량 내비가 안내할 수 있는 거리 범위를 벗어났어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE -> "차량 내비에서 경로를 찾지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "출발지와 목적지가 너무 가까워 경로를 찾지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_TRIP -> "차량 내비에서 이동 계획을 찾지 못했어요"
}
