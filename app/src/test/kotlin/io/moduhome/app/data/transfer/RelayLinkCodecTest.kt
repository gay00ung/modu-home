package io.moduhome.app.data.transfer

import io.moduhome.app.core.transfer.RelayReceiverSession
import io.moduhome.app.core.transfer.RelaySenderSession
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class RelayLinkCodecTest {
    private val now = 1_793_000_000_000L
    private val room = "f0322b13-c162-47b0-a983-00f21e9ddc92"
    private val token = "a".repeat(43)
    private fun sender(base: String = "https://relay.example.com") = RelaySenderSession(base, room, token, now + 900_000)

    @Test fun pairingRoundTripKeepsOnlySenderAuthority() {
        val original = sender()
        val result = RelayLinkCodec.decode(RelayLinkCodec.encode(original), now) as RelayJoinDecodeResult.Valid
        assertEquals(original.baseUrl, result.session.baseUrl)
        assertEquals(original.roomId, result.session.roomId)
        assertEquals(token, result.session.senderToken)
        assertEquals(original.expiresAtMillis, result.session.expiresAtMillis)
        val receiver = RelayReceiverSession("http://10.0.2.2:48680", room, "b".repeat(43), original)
        assertFalse(RelayLinkCodec.encode(receiver.sender).contains(receiver.receiverToken))
        assertFalse(receiver.toString().contains(receiver.receiverToken))
        assertFalse(original.toString().contains(token))
    }

    @Test fun developmentPrivateLanRequiresExplicitOptIn() {
        val link = RelayLinkCodec.encode(sender("http://192.168.10.5:48680"))
        assertTrue(RelayLinkCodec.decode(link, now) is RelayJoinDecodeResult.Invalid)
        assertTrue(RelayLinkCodec.decode(link, now, true) is RelayJoinDecodeResult.Valid)
    }

    @Test fun browserPairingPlacesTheSenderCredentialOnlyInTheFragment() {
        val sender = sender()
        val receiver = RelayReceiverSession(sender.baseUrl, room, "b".repeat(43), sender)
        val link = URI(RelayLinkCodec.encodeBrowser(receiver.sender))
        assertEquals("https", link.scheme)
        assertEquals("relay.example.com", link.host)
        assertEquals("/dropoff/", link.path)
        assertNull(link.rawQuery)
        assertEquals("v=1&room=$room&token=$token", link.rawFragment)
        assertFalse(link.toString().contains(receiver.receiverToken))
    }

    @Test fun onlyCanonicalPrivateHttpHostsAreAccepted() {
        listOf("10.0.2.2", "172.16.0.1", "172.31.255.1", "192.168.0.1", "127.0.0.1", "localhost", "[::1]").forEach {
            assertNotNull(it, RelayEndpoint.normalize("http://$it:48680", true))
        }
        listOf("8.8.8.8", "172.15.1.1", "172.32.0.1", "169.254.169.254", "0.0.0.0", "127.1", "2130706433",
            "0177.0.0.1", "192.168.1.256", "local.example.com").forEach {
            assertNull(it, RelayEndpoint.normalize("http://$it:48680", true))
        }
    }

    @Test fun endpointRejectsCredentialsPathsAndAmbiguousComponents() {
        listOf("https://user:password@example.com", "https://example.com/api", "https://example.com?room=a",
            "https://example.com#fragment", "ftp://example.com", "https://example.com:0", "https://example.com:65536",
            " https://example.com", "https://example.com/%2e", "https://예시.example").forEach {
            assertNull(it, RelayEndpoint.normalize(it, true))
        }
        assertEquals("https://example.com", RelayEndpoint.normalize("https://example.com/", false))
    }

    @Test fun expiryCannotBeExtendedByChangingTheLink() {
        val link = RelayLinkCodec.encode(sender())
        assertTrue(RelayLinkCodec.decode(link, now + 900_000) is RelayJoinDecodeResult.Invalid)
        assertTrue(RelayLinkCodec.decode(link.replace((now + 900_000).toString(), Long.MAX_VALUE.toString()), now) is RelayJoinDecodeResult.Invalid)
        assertTrue(RelayLinkCodec.decode(link, -1) is RelayJoinDecodeResult.Invalid)
    }

    @Test fun duplicateUnknownMalformedAndWrongAuthorityFieldsAreRejected() {
        val link = RelayLinkCodec.encode(sender())
        listOf("$link&v=1", "$link&extra=1", "$link#fragment", link.replace("v=1", "v=2"),
            link.replace("v=1", "v=%ZZ"), link.replace("token=$token", "token=short"),
            link.replace("//join?", "//user@join?"), link.replace("//join?", "//join/?"),
            link.replace("room=$room", "room=../../room"), link.replace("base=", "unknown=")).forEach {
            assertTrue(it.substringBefore("token="), RelayLinkCodec.decode(it, now) is RelayJoinDecodeResult.Invalid)
        }
    }
}
