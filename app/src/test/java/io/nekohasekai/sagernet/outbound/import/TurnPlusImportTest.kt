package app.rawline.mobile.outbound.import

import app.rawline.mobile.outbound.types.Qwdtt
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnPlusImportTest {
    // The shape a TURN+ subscription serves (application/json): its profiles are qWDTT servers.
    @Test fun turnPlusSubscriptionImportsQwdttProfiles() {
        val body = """{"subscriptionName":"TURNPLUS+","description":"Подписка навсегда","trafficUsedMb":0.0,"trafficLimitMb":0.0,"updatedAt":"2026-11-12","version":1,"profiles":[{"name":"Швеция #1","peer":"203.0.113.20:56000","password":"q_test","hashes":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","workersPerHash":16,"listenPort":9000},{"name":"Германия #1","peer":"203.0.113.21:56000","password":"q_test","hashes":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","workersPerHash":16,"listenPort":9000}]}"""
        val result = ProfileImport.parse(body)
        assertEquals(result.messages.toString(), 2, result.outbounds.size)
        val first = result.outbounds[0] as Qwdtt
        assertEquals("Швеция #1", first.name)
        assertEquals("203.0.113.20", first.server)
        assertEquals(56000, first.serverPort)
        assertEquals(listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), first.hashes)
    }
}
