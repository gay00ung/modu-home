package io.moduhome.app.data.navigation

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

private fun NaviErrorCode.userMessage(): String = when (this) {
    NaviErrorCode.CAUSE_LOW_BATTERY -> "차량 내비에서 배터리 부족으로 경로를 설정하지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "차량 내비가 안내할 수 있는 거리 범위를 벗어났어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE -> "차량 내비에서 경로를 찾지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "출발지와 목적지가 너무 가까워 경로를 찾지 못했어요"
    NaviErrorCode.CAUSE_SEARCH_TRIP -> "차량 내비에서 이동 계획을 찾지 못했어요"
}
