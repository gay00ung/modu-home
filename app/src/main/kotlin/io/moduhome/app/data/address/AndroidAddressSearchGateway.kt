package io.moduhome.app.data.address

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.address.AddressSearchBackend
import io.moduhome.app.core.address.AddressSearchGateway
import io.moduhome.app.core.address.AddressSearchResult
import io.moduhome.app.core.address.SingleFlightAddressSearchGateway
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * Explicit forward-address search, not device location. No API key or location permission.
 * Results are best-effort addresses: the user still selects and verifies the intended place.
 */
internal class AndroidAddressSearchGateway(context: Context) : AddressSearchGateway {
    private val applicationContext = context.applicationContext

    override suspend fun search(address: String): AddressSearchResult {
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AddressSearchResult.Unavailable("주소 검색은 Android 13 이상에서 사용할 수 있어요")
        }
        val available = try { Geocoder.isPresent() }
        catch (_: RuntimeException) {
            return AddressSearchResult.Failed("주소 검색 서비스를 확인하지 못했어요. 잠시 후 다시 시도해주세요")
        }
        if (!available) return AddressSearchResult.Unavailable("이 기기에서는 주소 검색을 사용할 수 없어요")
        return Api33.gateway(applicationContext).search(address)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        // Process-shared so Activity recreation/new wrappers cannot accumulate uncancellable
        // backend work. No callback means Busy until a callback arrives or the process restarts.
        private var shared: AddressSearchGateway? = null

        @Synchronized
        fun gateway(context: Context): AddressSearchGateway = shared ?: SingleFlightAddressSearchGateway(
            AddressSearchBackend { address, maximumResults, complete ->
                Geocoder(context.applicationContext, Locale.KOREA).getFromLocationName(
                    address, maximumResults,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: List<Address>) {
                            val candidates = addresses.take(maximumResults).mapNotNull { item ->
                                AddressCandidate.fromObservation(
                                    if (item.maxAddressLineIndex >= 0) item.getAddressLine(0) else null,
                                    if (item.hasLatitude()) item.latitude else null,
                                    if (item.hasLongitude()) item.longitude else null,
                                )
                            }.distinctBy { Triple(it.address, it.point.latitude, it.point.longitude) }
                            complete(if (candidates.isEmpty()) AddressSearchResult.NoResults
                                else AddressSearchResult.Candidates(candidates))
                        }

                        override fun onError(errorMessage: String?) {
                            // Provider error text is neither stable nor safe to expose verbatim.
                            complete(AddressSearchResult.Failed("주소를 검색하지 못했어요. 연결 상태를 확인한 뒤 다시 시도해주세요"))
                        }
                    },
                )
            },
        ).also { shared = it }
    }
}
