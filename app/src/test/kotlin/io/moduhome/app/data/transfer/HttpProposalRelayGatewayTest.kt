package io.moduhome.app.data.transfer

import io.moduhome.app.core.transfer.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpProposalRelayGatewayTest {
    private val room = "f0322b13-c162-47b0-a983-00f21e9ddc92"
    private val messageId = "45155591-fd06-4e25-b112-13d56a374ea7"
    private val senderToken = "a".repeat(43)
    private val receiverToken = "b".repeat(43)
    private val link = "modu-home://proposal?v=1"
    private fun sender(base: String) = RelaySenderSession(base, room, senderToken, System.currentTimeMillis() + 900_000)
    private fun receiver(base: String) = RelayReceiverSession(base, room, receiverToken, sender(base))

    @Test fun roomSeparatesPrivateReceiverEndpointFromPublicPairingEndpoint() = runBlocking {
        Stub().use { server ->
            server.reply(201, JSONObject().put("roomId", room).put("receiverToken", receiverToken)
                .put("senderToken", senderToken).put("expiresAtMillis", System.currentTimeMillis() + 900_000)
                .put("publicBaseUrl", "http://192.168.1.10:48680"))
            val result = HttpProposalRelayGateway(server.base, true).openRoom(link) as RelayResult.Success
            assertEquals(server.base, result.value.baseUrl)
            assertEquals("http://192.168.1.10:48680", result.value.sender.baseUrl)
            assertEquals(link, JSONObject(server.lastBody).getString("seedLink"))
            assertNull(server.lastAuthorization)
            assertEquals("/v1/rooms", server.lastPath)
        }
    }

    @Test fun joinRequiresOriginalExpiryAndReadsOnlySenderSeed() = runBlocking {
        Stub().use { server ->
            val session = sender(server.base)
            val gateway = HttpProposalRelayGateway("", true)
            server.reply(200, JSONObject().put("expiresAtMillis", session.expiresAtMillis).put("seedLink", link))
            val joined = gateway.join(session) as RelayResult.Success
            assertEquals(link, joined.value.seedLink)
            assertEquals("Bearer $senderToken", server.lastAuthorization)
            server.reply(200, JSONObject().put("expiresAtMillis", session.expiresAtMillis + 1).put("seedLink", JSONObject.NULL))
            assertEquals(RelayFailure.INVALID_RESPONSE, (gateway.join(session) as RelayResult.Failure).code)
        }
    }

    @Test fun receiverPollAndSenderDeliveryUseDifferentCredentials() = runBlocking {
        Stub().use { server ->
            val gateway = HttpProposalRelayGateway(server.base, true)
            server.reply(200, JSONObject().put("messages", JSONArray().put(JSONObject().put("messageId", messageId).put("link", link))))
            val messages = (gateway.pending(receiver(server.base)) as RelayResult.Success).value
            assertEquals(listOf(RelayMessage(messageId, link)), messages)
            assertEquals("Bearer $receiverToken", server.lastAuthorization)
            server.reply(200, JSONObject().put("status", "queued"))
            assertEquals(RelayDeliveryStatus.QUEUED, (gateway.delivery(sender(server.base), messageId) as RelayResult.Success).value)
            assertEquals("Bearer $senderToken", server.lastAuthorization)
            assertFalse(server.lastPath.contains(senderToken))
        }
    }

    @Test fun sendingKeepsMessageIdentityAndReportsQueueBeforeStorage() = runBlocking {
        Stub().use { server ->
            val gateway = HttpProposalRelayGateway(server.base, true)
            val message = RelayMessage(messageId, link)
            server.reply(202, JSONObject().put("status", "queued"))
            assertEquals(RelayDeliveryStatus.QUEUED, (gateway.send(sender(server.base), message) as RelayResult.Success).value)
            val originalBody = server.lastBody
            assertEquals(messageId, JSONObject(originalBody).getString("messageId"))
            server.reply(202, JSONObject().put("status", "stored"))
            assertEquals(RelayDeliveryStatus.STORED, (gateway.send(sender(server.base), message) as RelayResult.Success).value)
            assertEquals(originalBody, server.lastBody)
        }
    }

    @Test fun acknowledgementMustMatchTheActualDurableOutcome() = runBlocking {
        Stub().use { server ->
            val gateway = HttpProposalRelayGateway(server.base, true)
            server.reply(200, JSONObject().put("status", "stored"))
            assertTrue(gateway.acknowledge(receiver(server.base), messageId, RelayDeliveryStatus.STORED) is RelayResult.Success)
            assertEquals("Bearer $receiverToken", server.lastAuthorization)
            assertEquals("stored", JSONObject(server.lastBody).getString("status"))
            assertEquals(RelayFailure.INVALID_RESPONSE,
                (gateway.acknowledge(receiver(server.base), messageId, RelayDeliveryStatus.REJECTED) as RelayResult.Failure).code)
            assertEquals(RelayFailure.CONFIGURATION,
                (gateway.acknowledge(receiver(server.base), messageId, RelayDeliveryStatus.QUEUED) as RelayResult.Failure).code)
        }
    }

    @Test fun terminalAndRetryableServerFailuresRemainDistinct() = runBlocking {
        Stub().use { server ->
            val gateway = HttpProposalRelayGateway(server.base, true)
            mapOf(401 to RelayFailure.UNAUTHORIZED, 410 to RelayFailure.CLOSED, 429 to RelayFailure.FULL,
                409 to RelayFailure.CONFLICT, 500 to RelayFailure.INVALID_RESPONSE).forEach { (status, failure) ->
                server.reply(status, JSONObject().put("error", "private-server-detail"))
                val result = gateway.pending(receiver(server.base)) as RelayResult.Failure
                assertEquals(failure, result.code)
                assertFalse(result.reason.contains("private-server-detail"))
            }
        }
    }

    @Test fun redirectsNeverForwardPairingCredentials() = runBlocking {
        Stub().use { destination ->
            Stub().use { source ->
                source.redirect = destination.base + "/v1/rooms"
                source.reply(307, JSONObject())
                assertTrue(HttpProposalRelayGateway(source.base, true).pending(receiver(source.base)) is RelayResult.Failure)
                assertEquals(0, destination.requests)
            }
        }
    }

    @Test fun invalidDuplicateOversizedAndWrongTypedResponsesAreRejected() = runBlocking {
        Stub().use { server ->
            val gateway = HttpProposalRelayGateway(server.base, true)
            val item = JSONObject().put("messageId", messageId).put("link", link)
            server.reply(200, JSONObject().put("messages", JSONArray().put(item).put(item)))
            assertTrue(gateway.pending(receiver(server.base)) is RelayResult.Failure)
            server.reply(200, JSONObject().put("messages", JSONArray()).put("extra", true))
            assertTrue(gateway.pending(receiver(server.base)) is RelayResult.Failure)
            server.reply(200, JSONObject().put("messages", "a".repeat(300 * 1_024)))
            assertTrue(gateway.pending(receiver(server.base)) is RelayResult.Failure)
            server.reply(200, JSONObject().put("status", "applied"))
            assertTrue(gateway.delivery(sender(server.base), messageId) is RelayResult.Failure)
        }
    }

    @Test fun closingRequiresReceiverAndAcceptsOnlyNoContentSuccess() = runBlocking {
        Stub().use { server ->
            server.reply(204, JSONObject())
            assertTrue(HttpProposalRelayGateway(server.base, true).close(receiver(server.base)) is RelayResult.Success)
            assertEquals("DELETE", server.lastMethod)
            assertEquals("Bearer $receiverToken", server.lastAuthorization)
        }
    }

    @Test fun cancellationDoesNotReturnALateSuccess() = runBlocking {
        Stub().use { server ->
            server.reply(200, JSONObject().put("messages", JSONArray()))
            server.responseDelayMillis = 4_000
            val returned = AtomicBoolean(false)
            val job = launch(Dispatchers.Default) {
                HttpProposalRelayGateway(server.base, true).pending(receiver(server.base))
                returned.set(true)
            }
            assertTrue(server.received.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("Cancellation took $elapsedMillis ms", elapsedMillis < 1_500)
            assertTrue(job.isCancelled)
            assertFalse(returned.get())
        }
    }

    private class Stub : AutoCloseable {
        private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val base = "http://127.0.0.1:${listener.localPort}"
        @Volatile var status = 200
        @Volatile var body = "{}"
        @Volatile var redirect: String? = null
        @Volatile var responseDelayMillis = 0L
        @Volatile var requests = 0
        @Volatile var lastBody = ""
        @Volatile var lastPath = ""
        @Volatile var lastMethod = ""
        @Volatile var lastAuthorization: String? = null
        @Volatile private var closed = false
        val received = CountDownLatch(1)
        private val worker = thread(name = "proposal-relay-test", isDaemon = true) {
            while (!closed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream().buffered()
                        val request = input.line().split(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.line()
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            require(colon > 0)
                            headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                        }
                        val bodyBytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < bodyBytes.size) {
                            val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
                            require(count > 0)
                            offset += count
                        }
                        requests += 1
                        lastBody = bodyBytes.toString(Charsets.UTF_8)
                        lastMethod = request[0]
                        lastPath = request[1]
                        lastAuthorization = headers["authorization"]
                        received.countDown()
                        if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis)
                        val bytes = if (status == 204) byteArrayOf() else body.toByteArray(Charsets.UTF_8)
                        val response = buildString {
                            append("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n")
                            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
                            redirect?.let { append("Location: $it\r\n") }
                            append("\r\n")
                        }
                        socket.getOutputStream().use { output ->
                            output.write(response.toByteArray(Charsets.US_ASCII))
                            output.write(bytes)
                            output.flush()
                        }
                    }
                } catch (_: IOException) {
                    // Closing the listener or cancelling the client closes a socket deliberately.
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        fun reply(code: Int, json: JSONObject) { status = code; body = json.toString() }
        override fun close() {
            closed = true
            listener.close()
            worker.interrupt()
            worker.join(3_000)
            check(!worker.isAlive) { "Test server did not stop" }
        }
        private fun BufferedInputStream.line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = read()
                if (byte < 0) throw IOException("Unexpected end of request")
                if (byte == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                require(bytes.size() < 16_384)
                bytes.write(byte)
            }
        }
    }
}
