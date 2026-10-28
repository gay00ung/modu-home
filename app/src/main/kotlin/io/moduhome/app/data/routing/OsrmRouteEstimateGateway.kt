package io.moduhome.app.data.routing

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateFailure
import io.moduhome.app.core.routing.RouteEstimateGateway
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Calls a configured OSRM instance; it neither starts guidance nor uploads to a public demo server. */
internal class OsrmRouteEstimateGateway(
    baseUrl: String,
    allowLocalHttp: Boolean = false,
) : RouteEstimateGateway {
    private val endpoint = validateBaseUrl(baseUrl, allowLocalHttp)

    override suspend fun estimate(points: List<NavigationPoint>): RouteEstimateResult {
        if (points.size !in 2..RouteEstimate.MAX_ROUTE_POINTS) {
            return RouteEstimateResult.Failed(RouteEstimateFailure.INVALID_REQUEST, "출발지와 방문할 장소를 다시 확인해주세요")
        }
        val orderedPoints = points.toList()
        return withTimeoutOrNull(TOTAL_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) { request(orderedPoints) }
        } ?: RouteEstimateResult.Failed(RouteEstimateFailure.TIMEOUT, "경로 조회가 지연됐어요. 다시 시도해주세요")
    }

    private suspend fun request(points: List<NavigationPoint>): RouteEstimateResult {
        val context = currentCoroutineContext()
        context.ensureActive()
        val coordinates = points.joinToString(";") { "${coordinate(it.longitude)},${coordinate(it.latitude)}" }
        val radiuses = points.joinToString(";") { RouteEstimate.MAX_SNAP_METERS.toInt().toString() }
        val url = URL("$endpoint/route/v1/driving/$coordinates?overview=false&steps=false&alternatives=false&continue_straight=false&radiuses=$radiuses")
        var connection: HttpURLConnection? = null
        val queriedAtMillis = System.currentTimeMillis()
        return try {
            val request = url.openConnection() as HttpURLConnection
            connection = request
            request.requestMethod = "GET"
            request.connectTimeout = 5_000
            request.readTimeout = 10_000
            request.instanceFollowRedirects = false
            request.setRequestProperty("Accept", "application/json")
            request.setRequestProperty("Accept-Encoding", "identity")
            context.ensureActive()
            val status = request.responseCode
            if (status != 200 && status != 400) {
                return RouteEstimateResult.Failed(RouteEstimateFailure.HTTP, "경로 서버가 요청을 처리하지 못했어요")
            }
            if (request.contentLengthLong > MAX_RESPONSE_BYTES) return invalidResponse()
            val stream = (if (status == 400) request.errorStream else request.inputStream) ?: return invalidResponse()
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    context.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_RESPONSE_BYTES) return invalidResponse()
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            context.ensureActive()
            val body = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            parseResponse(body, points, queriedAtMillis, status)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            RouteEstimateResult.Failed(RouteEstimateFailure.TIMEOUT, "경로 조회가 지연됐어요. 다시 시도해주세요")
        } catch (_: CharacterCodingException) {
            invalidResponse()
        } catch (_: JSONException) {
            invalidResponse()
        } catch (_: IllegalArgumentException) {
            invalidResponse()
        } catch (_: IOException) {
            context.ensureActive()
            RouteEstimateResult.Failed(RouteEstimateFailure.NETWORK, "경로 서버에 연결하지 못했어요")
        } catch (_: SecurityException) {
            context.ensureActive()
            RouteEstimateResult.Failed(RouteEstimateFailure.NETWORK, "경로 서버의 네트워크 연결 권한을 확인해주세요")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(
        body: String,
        points: List<NavigationPoint>,
        queriedAtMillis: Long,
        httpStatus: Int,
    ): RouteEstimateResult {
        val response = JSONObject(body)
        when (response.getString("code")) {
            "NoRoute" -> return RouteEstimateResult.Failed(RouteEstimateFailure.NO_ROUTE, "선택한 장소를 잇는 도로 경로가 없어요")
            "NoSegment" -> return RouteEstimateResult.Failed(RouteEstimateFailure.NO_SEGMENT, "도로와 연결할 수 없는 장소가 있어요. 출입구 위치를 확인해주세요")
            "Ok" -> if (httpStatus != 200) return invalidResponse()
            else -> return invalidResponse()
        }
        val routes = response.getJSONArray("routes")
        if (routes.length() != 1) return invalidResponse()
        val route = routes.getJSONObject(0)
        val legs = route.getJSONArray("legs")
        val waypoints = response.getJSONArray("waypoints")
        if (legs.length() != points.size - 1 || waypoints.length() != points.size) return invalidResponse()
        val observedLegs = (0 until legs.length()).map { index ->
            val leg = legs.getJSONObject(index)
            ObservedRouteLeg(number(leg, "duration"), number(leg, "distance"))
        }
        val snappedPoints = (0 until waypoints.length()).map { index ->
            val waypoint = waypoints.getJSONObject(index)
            val location = waypoint.getJSONArray("location")
            if (location.length() != 2) return invalidResponse()
            val longitude = location.get(0) as? Number ?: return invalidResponse()
            val latitude = location.get(1) as? Number ?: return invalidResponse()
            SnappedRoutePoint(latitude.toDouble(), longitude.toDouble(), number(waypoint, "distance"))
        }
        val version = if (response.has("data_version") && !response.isNull("data_version")) {
            response.get("data_version") as? String ?: return invalidResponse()
        } else null
        return RouteEstimate.fromObservation(points, observedLegs, snappedPoints, number(route, "duration"),
            number(route, "distance"), queriedAtMillis, version)
    }

    private fun number(value: JSONObject, key: String): Double =
        (value.get(key) as? Number)?.toDouble() ?: throw JSONException("Expected a numeric route field")

    private fun coordinate(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun invalidResponse(): RouteEstimateResult.Failed =
        RouteEstimateResult.Failed(RouteEstimateFailure.INVALID_RESPONSE, "경로 서버의 응답을 확인하지 못했어요")

    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576
        private const val TOTAL_TIMEOUT_MILLIS = 15_000L

        private fun validateBaseUrl(baseUrl: String, allowLocalHttp: Boolean): String {
            val uri = URI(baseUrl)
            val host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("A route server host is required")
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.orEmpty() in listOf("", "/"))
            require(uri.port == -1 || uri.port in 1..65_535)
            val isLocal = host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
            require(uri.scheme == "https" || (uri.scheme == "http" && allowLocalHttp && isLocal))
            return uri.toASCIIString().trimEnd('/')
        }
    }
}
