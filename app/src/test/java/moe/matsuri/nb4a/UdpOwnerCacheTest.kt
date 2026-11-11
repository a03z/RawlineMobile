package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpOwnerCacheTest {
    @Test
    fun missUsesRecentOwnerOfSameFlowOnly() {
        UdpOwnerCache.clear()
        assertEquals(10123, UdpOwnerCache.resolve("a", 10123, 1_000))
        assertEquals(10123, UdpOwnerCache.resolve("a", UdpOwnerCache.NOT_FOUND, 1_000 + UdpOwnerCache.TTL_MS))
        assertEquals(UdpOwnerCache.NOT_FOUND, UdpOwnerCache.resolve("a", UdpOwnerCache.NOT_FOUND, 1_001 + UdpOwnerCache.TTL_MS))
        assertEquals(UdpOwnerCache.NOT_FOUND, UdpOwnerCache.resolve("b", UdpOwnerCache.NOT_FOUND, 1_000))
    }
}
