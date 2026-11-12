package moe.matsuri.nb4a

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OwnerLookupHealthTest {
    @Before fun clean() = OwnerLookupHealth.reset()

    @Test fun noAppOwnerEverMeansBroken() {
        repeat(OwnerLookupHealth.MISSES - 1) { assertFalse(OwnerLookupHealth.tcp(-1)) }
        assertFalse(OwnerLookupHealth.tcp(1000)) // system owners prove nothing
        assertTrue(OwnerLookupHealth.tcp(-1))
        assertTrue(OwnerLookupHealth.tcp(10_123))
    }

    @Test fun oneAppOwnerKeepsTheGuard() {
        assertFalse(OwnerLookupHealth.tcp(10_123))
        repeat(OwnerLookupHealth.MISSES * 2) { assertFalse(OwnerLookupHealth.tcp(-1)) }
    }
}
