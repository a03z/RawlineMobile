package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.types.AutoSelector
import app.rawline.mobile.outbound.types.Shadowsocks
import org.junit.Assert.*
import org.junit.Test

class SelectorTiersTest {
    private val now = 1000L
    private fun member(id: Long, latency: Int) = SelectorMember(id,
        Shadowsocks().apply { name = "Server $id"; server = "192.0.2.1"; serverPort = 8388; method = "aes-256-gcm"; password = "secret" },
        latency, now, "")

    private val groups = mapOf(
        5L to SelectorGroup(5, "First", -1, -1, null, null, listOf(member(1, 300), member(2, 400))),
        6L to SelectorGroup(6, "Second", -1, -1, null, null, listOf(member(3, 10), member(4, 20), member(5, 30))),
    )

    @Test fun groupsInTheirOrderOutrankFasterMembersOfLaterGroups() {
        val planner = AutoSelectorPlanner(SelectorStore { groups[it] }, { now })
        val selector = AutoSelector().apply { gids = mutableListOf(5, 6); strategy = "priority"; tiers = mutableListOf(AutoSelector.Tier(5), AutoSelector.Tier(6)) }
        val plan = planner.plan(10, selector)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.pool)
        assertEquals(mapOf(1L to 0, 2L to 0, 3L to 1, 4L to 1, 5L to 1), plan.tierOf)
        // A tight build limit still keeps a share of the later group to fall back on.
        selector.buildLimit = 3
        assertTrue(planner.plan(10, selector).build.any { it >= 3 })
        // The latency strategy ranks every member together.
        selector.strategy = "latency"; selector.buildLimit = 300
        assertEquals(3L, planner.plan(10, selector).pool.first())
    }

    @Test fun tiersSurviveJson() {
        val selector = AutoSelector().apply { gids = mutableListOf(5, 6); strategy = "priority"; tiers = mutableListOf(AutoSelector.Tier(5), AutoSelector.Tier(-1, "DE")); failbackChecks = 4 }
        val restored = AutoSelector().apply { parseFromJson(selector.exportToJson()) }
        assertTrue(restored.prioritized())
        assertEquals(listOf(5L to "", -1L to "DE"), restored.tiers.map { it.gid to it.nameFilter })
        assertEquals(4, restored.failbackChecks)
    }
}
