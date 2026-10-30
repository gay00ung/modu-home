package io.moduhome.app.data.transfer

import io.moduhome.app.core.transfer.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Caller-owned requests. Pairing credentials stay in memory and are never included in errors. */
internal class HttpProposalRelayGateway(
    baseUrl: String,
    private val allowLocalHttp: Boolean = false,
) : ProposalRelayGateway {
    private val endpoint = RelayEndpoint.normalize(baseUrl, allowLocalHttp)

    override suspend fun openRoom(seedLink: String?): RelayResult<RelayReceiverSession> {
        val base = endpoint ?: return configurationFailure()
        if (seedLink != null && !RelayIdentifiers.validLink(seedLink)) return configurationFailure()
        val body = JSONObject().apply { seedLink?.let { put("seedLink", it) } }
        return request(base, "/v1/rooms", "POST", body = body, expectedStatus = 201) { root ->
            requireKeys(root, "roomId", "receiverToken", "senderToken", "expiresAtMillis", "publicBaseUrl")
            val publicBase = RelayEndpoint.normalize(root.getString("publicBaseUrl"), allowLocalHttp)
                ?: throw JSONException("Invalid endpoint")
            val expires = expiry(root)
            val sender = RelaySenderSession(publicBase, root.getString("roomId"), root.getString("senderToken"), expires)
            RelayReceiverSession(base, sender.roomId, root.getString("receiverToken"), sender)
        }
    }

    override suspend fun join(session: RelaySenderSession): RelayResult<RelayRoomWelcome> =
        request(session.baseUrl, roomPath(session.roomId), "GET", session.senderToken) { root ->
            requireKeys(root, "expiresAtMillis", "seedLink")
            val expires = expiry(root)
            require(expires == session.expiresAtMillis)
            RelayRoomWelcome(expires, if (root.isNull("seedLink")) null else root.getString("seedLink"))
        }

    override suspend fun pending(session: RelayReceiverSession): RelayResult<List<RelayMessage>> =
        request(session.baseUrl, roomPath(session.roomId) + "/messages", "GET", session.receiverToken) { root ->
            requireKeys(root, "messages")
            val values = root.getJSONArray("messages")
            require(values.length() <= 20)
            val messages = (0 until values.length()).map { index ->
                val item = values.getJSONObject(index)
                requireKeys(item, "messageId", "link")
                RelayMessage(item.getString("messageId"), item.getString("link"))
            }
            require(messages.map { it.messageId }.distinct().size == messages.size)
            messages
        }

    override suspend fun acknowledge(
        session: RelayReceiverSession,
        messageId: String,
        status: RelayDeliveryStatus,
    ): RelayResult<Unit> {
        if (!RelayIdentifiers.validId(messageId) || status == RelayDeliveryStatus.QUEUED) return configurationFailure()
        return request(session.baseUrl, messagePath(session.roomId, messageId) + "/ack", "POST", session.receiverToken,
            JSONObject().put("status", status.name.lowercase())) { root ->
            require(parseStatus(root) == status)
            Unit
        }
    }

    override suspend fun send(session: RelaySenderSession, message: RelayMessage): RelayResult<RelayDeliveryStatus> =
        request(session.baseUrl, roomPath(session.roomId) + "/messages", "POST", session.senderToken,
            JSONObject().put("messageId", message.messageId).put("link", message.link), 202, ::parseStatus)

    override suspend fun delivery(session: RelaySenderSession, messageId: String): RelayResult<RelayDeliveryStatus> {
        if (!RelayIdentifiers.validId(messageId)) return configurationFailure()
        return request(session.baseUrl, messagePath(session.roomId, messageId), "GET", session.senderToken, parse = ::parseStatus)
    }

    override suspend fun close(session: RelayReceiverSession): RelayResult<Unit> =
        request(session.baseUrl, roomPath(session.roomId), "DELETE", session.receiverToken, expectedStatus = 204) { Unit }

    private suspend fun <T> request(
        base: String,
        path: String,
        method: String,
        token: String? = null,
        body: JSONObject? = null,
        expectedStatus: Int = 200,
        parse: (JSONObject) -> T,
    ): RelayResult<T> {
        val normalized = RelayEndpoint.normalize(base, allowLocalHttp) ?: return configurationFailure()
        return withTimeoutOrNull(8_000) {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                context.ensureActive()
                try {
                    val request = URL(normalized + path).openConnection() as HttpURLConnection
                    coroutineScope {
                        val operation = async {
                            request.requestMethod = method
                            request.connectTimeout = 4_000
                            request.readTimeout = 4_000
                            request.instanceFollowRedirects = false
                            request.useCaches = false
                            request.setRequestProperty("Accept", "application/json")
                            request.setRequestProperty("Accept-Encoding", "identity")
                            request.setRequestProperty("Connection", "close")
                            token?.let { request.setRequestProperty("Authorization", "Bearer $it") }
                            body?.let {
                                val bytes = it.toString().toByteArray(Charsets.UTF_8)
                                require(bytes.size <= 12 * 1_024)
                                request.doOutput = true
                                request.setFixedLengthStreamingMode(bytes.size)
                                request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                request.outputStream.use { output -> output.write(bytes) }
                            }
                            context.ensureActive()
                            val status = request.responseCode
                            if (status != expectedStatus) return@async httpFailure(status)
                            if (status == 204) return@async RelayResult.Success(parse(JSONObject()))
                            if (request.contentLengthLong > MAX_RESPONSE_BYTES ||
                                request.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                                return@async invalidResponse()
                            }
                            val bytes = request.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    context.ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    if (output.size() + size > MAX_RESPONSE_BYTES) return@async invalidResponse()
                                    output.write(buffer, 0, size)
                                }
                                output.toByteArray()
                            }
                            context.ensureActive()
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                            RelayResult.Success(parse(JSONObject(text)))
                        }
                        // The awaiting owner can close the socket while its child is blocked in IO.
                        try { operation.await() } finally { request.disconnect() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: JSONException) {
                    invalidResponse()
                } catch (_: IllegalArgumentException) {
                    invalidResponse()
                } catch (_: CharacterCodingException) {
                    invalidResponse()
                } catch (_: IOException) {
                    context.ensureActive()
                    connectionFailure()
                } catch (_: SecurityException) {
                    context.ensureActive()
                    connectionFailure()
                }
            }
        } ?: connectionFailure()
    }

    private fun expiry(root: JSONObject): Long {
        val value = root.get("expiresAtMillis")
        require(value is Long || value is Int)
        val expires = (value as Number).toLong()
        val now = System.currentTimeMillis()
        require(expires > now && expires - now <= RelayLinkCodec.MAX_PAIRING_LIFETIME_MILLIS)
        return expires
    }

    private fun parseStatus(root: JSONObject): RelayDeliveryStatus {
        requireKeys(root, "status")
        return when (root.getString("status")) {
            "queued" -> RelayDeliveryStatus.QUEUED
            "stored" -> RelayDeliveryStatus.STORED
            "rejected" -> RelayDeliveryStatus.REJECTED
            else -> throw JSONException("Invalid status")
        }
    }

    private fun requireKeys(root: JSONObject, vararg expected: String) {
        require(root.keys().asSequence().toSet() == expected.toSet())
    }

    private fun roomPath(roomId: String): String = "/v1/rooms/$roomId"
    private fun messagePath(roomId: String, messageId: String): String = roomPath(roomId) + "/messages/$messageId"
    private fun configurationFailure() = RelayResult.Failure(RelayFailure.CONFIGURATION, "차량 연결 주소를 확인해주세요")
    private fun connectionFailure() = RelayResult.Failure(RelayFailure.CONNECTION, "연결이 닿지 않았어요. 네트워크 상태를 확인한 뒤 다시 시도해주세요")
    private fun invalidResponse() = RelayResult.Failure(RelayFailure.INVALID_RESPONSE, "연결 응답을 확인하지 못했어요. 다시 연결해주세요")
    private fun httpFailure(status: Int): RelayResult.Failure = when (status) {
        401 -> RelayResult.Failure(RelayFailure.UNAUTHORIZED, "연결 권한을 확인하지 못했어요. QR로 다시 연결해주세요")
        410 -> RelayResult.Failure(RelayFailure.CLOSED, "연결이 끝났어요. 차량에서 새 QR을 열어주세요")
        429 -> RelayResult.Failure(RelayFailure.FULL, "전송 대기가 많아요. 잠시 뒤 다시 시도해주세요")
        409 -> RelayResult.Failure(RelayFailure.CONFLICT, "이 전송의 내용이 바뀌었어요. 새 제안을 만들어주세요")
        else -> invalidResponse()
    }

    companion object { private const val MAX_RESPONSE_BYTES = 256 * 1_024 }
}
