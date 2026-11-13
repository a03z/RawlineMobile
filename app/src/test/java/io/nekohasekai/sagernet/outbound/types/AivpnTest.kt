package app.rawline.mobile.outbound.types

import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.outbound.import.ProfileImport
import org.junit.Assert.*
import org.junit.Test

class AivpnTest {
    private fun key(server: String) = "aivpn://" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"s":"$server","k":"c2VydmVyLWtleQ","p":"cHNr","i":"10.0.0.2"}""".toByteArray())

    @Test fun keyLinkBecomesAProfile() {
        val profile = ProfileImport.parse(key("203.0.113.5:8443")).also { assertEquals(it.messages.toString(), 1, it.outbounds.size) }.outbounds.single() as Aivpn
        assertEquals("203.0.113.5", profile.server)
        assertEquals(8443, profile.serverPort)
        assertEquals("auto", profile.mask)
        assertEquals(key("203.0.113.5:8443"), profile.exportToLink())
        val v6 = OutboundFactory.parseLink(key("[2001:db8::1]:443")) as Aivpn
        assertEquals("2001:db8::1", v6.server)
    }

    @Test fun jsonKeepsEverySettingAndTheLaunchFileMatchesThePatch() {
        val profile = Aivpn().apply { parseFromLink(key("203.0.113.5:443")); mask = "quic_https_v2"; polymorphic = true; adaptiveLevel = 2; dns = "1.1.1.1, 9.9.9.9" }
        val restored = ProfileImport.parse(profile.exportToJson().toCompact()).outbounds.single() as Aivpn
        assertEquals(profile.exportToJson().toCompact(), restored.exportToJson().toCompact())
        val config = restored.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))
        assertEquals("127.0.0.1:1080", config.string("socks"))
        assertTrue(config.string("admin").startsWith("127.0.0.1:") && config.string("admin") != "127.0.0.1:1080")
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), config.array("dns").strings())
        assertEquals("quic_https_v2", config.string("mask"))
        assertEquals(2, config.int("adaptive_level"))
    }

    @Test fun invalidSettingsAreRefused() {
        assertFalse(Aivpn().parseFromLink("aivpn://not-base64!"))
        val base = Aivpn().apply { parseFromLink(key("203.0.113.5:443")) }
        assertFalse(base.apply { mask = "unknown" }.valid())
        assertFalse(Aivpn().apply { parseFromLink(key("203.0.113.5:443")); dns = "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5" }.valid())
        assertFalse(Aivpn().apply { parseFromLink(key("203.0.113.5:443")); dns = "dns.example" }.valid())
    }
}
