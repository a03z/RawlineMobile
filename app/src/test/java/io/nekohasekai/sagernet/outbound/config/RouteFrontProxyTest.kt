package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Chain
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.route.RouteProfile
import app.rawline.mobile.route.RouteRule
import app.rawline.mobile.route.RuleSetCatalog
import org.junit.Assert.*
import org.junit.Test

/**
 * A route rule naming a profile of a group with a landing / front proxy must dial it the same way the main chain
 * does: exit first, then the landing proxy in front of it, the front proxy as the directly dialed entry. A bare
 * outbound strands the chain on networks where the server is only reachable through the front.
 */
class RouteFrontProxyTest {
    private class Provider(
        private val profiles: Map<Long, Outbound>,
        private val groups: Map<Long, Pair<Long, Long>> = emptyMap(),
    ) : ProfileProvider {
        override fun get(id: Long): Outbound? = profiles[id]
        override fun groupProxies(profileId: Long): Pair<Long, Long> = groups[profileId] ?: (-1L to -1L)
    }

    private fun socks(server: String) = Socks().apply { this.server = server; serverPort = 1080 }

    private fun routingTo(outboundId: Long): RoutingInput {
        val rule = RouteRule().apply { domain.add("example.com"); action = "route"; outbound_id = outboundId }
        return RoutingInput(RouteProfile().apply { rules.add(rule) }, RuleSetCatalog(emptyList()))
    }

    private fun outbounds(result: GeneratedConfig) =
        JsonInput.parseObject(result.coreConfig).array("outbounds").filterIsInstance<JsonObject>()
            .associateBy { it.string("tag") }

    @Test fun routeRuleThroughFrontProxyGroupDialsTheFrontDirectly() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20"); val front = socks("192.0.2.30")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit, 3L to front)
        val result = ConfigGenerator(Provider(profiles, mapOf(2L to (-1L to 3L))), GeneratorSettings(),
            routing = routingTo(2L)).build(1L)
        assertNull(result.error)
        val box = outbounds(result)
        assertEquals("192.0.2.20", box.getValue("route-0").string("server"))
        assertEquals("route-1", box.getValue("route-0").string("detour"))
        assertEquals("192.0.2.30", box.getValue("route-1").string("server"))
        assertFalse(box.getValue("route-1").contains("detour"))
        // The rule keeps naming route-0, the chain grew around it.
        val rules = JsonInput.parseObject(result.coreConfig).obj("route").array("rules").filterIsInstance<JsonObject>()
        assertTrue(rules.any { it.string("outbound") == "route-0" && it.array("domain").contains("example.com") })
    }

    @Test fun landingAndFrontWrapTheRouteOutboundInChainOrder() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20"); val front = socks("192.0.2.30")
        val landing = socks("192.0.2.40")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit, 3L to front, 4L to landing)
        val result = ConfigGenerator(Provider(profiles, mapOf(2L to (4L to 3L))), GeneratorSettings(),
            routing = routingTo(2L)).build(1L)
        assertNull(result.error)
        val box = outbounds(result)
        // Exit-first like the main chain: the ingress the rule names is the landing proxy, the front is dialed directly.
        assertEquals("192.0.2.40", box.getValue("route-0").string("server"))
        assertEquals("route-1", box.getValue("route-0").string("detour"))
        assertEquals("192.0.2.20", box.getValue("route-1").string("server"))
        assertEquals("route-2", box.getValue("route-1").string("detour"))
        assertEquals("192.0.2.30", box.getValue("route-2").string("server"))
        assertFalse(box.getValue("route-2").contains("detour"))
    }

    @Test fun chainRouteOutboundGrowsTheGroupsFrontBehindItsHops() {
        val main = socks("192.0.2.10"); val entry = socks("192.0.2.20"); val exit = socks("192.0.2.21")
        val front = socks("192.0.2.30")
        val chain = Chain().apply { list = arrayListOf(11L, 12L) } // stored in -> out
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to chain, 3L to front, 11L to entry, 12L to exit)
        val result = ConfigGenerator(Provider(profiles, mapOf(2L to (-1L to 3L))), GeneratorSettings(),
            routing = routingTo(2L)).build(1L)
        assertNull(result.error)
        val box = outbounds(result)
        assertEquals("192.0.2.21", box.getValue("route-0").string("server"))
        assertEquals("route-1", box.getValue("route-0").string("detour"))
        assertEquals("192.0.2.20", box.getValue("route-1").string("server"))
        assertEquals("route-2", box.getValue("route-1").string("detour"))
        assertEquals("192.0.2.30", box.getValue("route-2").string("server"))
    }

    @Test fun danglingFrontProxyLeavesTheRouteOutboundBareInsteadOfFailingTheStart() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit)
        val result = ConfigGenerator(Provider(profiles, mapOf(2L to (-1L to 99L))), GeneratorSettings(),
            routing = routingTo(2L)).build(1L)
        assertNull(result.error)
        val box = outbounds(result)
        assertEquals("192.0.2.20", box.getValue("route-0").string("server"))
        assertFalse(box.getValue("route-0").contains("detour"))
    }

    @Test fun twoRulesSharingTheProfileShareOneTaggedChain() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20"); val front = socks("192.0.2.30")
        val other = socks("192.0.2.50")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit, 3L to front, 4L to other)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { domain.add("a.example"); action = "route"; outbound_id = 2L })
            rules.add(RouteRule().apply { domain.add("b.example"); action = "route"; outbound_id = 4L })
        }
        // Both groups share the same front: the second chain numbers after the first one's hops.
        val groups = mapOf(2L to (-1L to 3L), 4L to (-1L to 3L))
        val result = ConfigGenerator(Provider(profiles, groups), GeneratorSettings(),
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)
        val box = outbounds(result)
        assertEquals("192.0.2.20", box.getValue("route-0").string("server"))
        assertEquals("route-1", box.getValue("route-0").string("detour"))
        assertEquals("192.0.2.30", box.getValue("route-1").string("server"))
        assertEquals("192.0.2.50", box.getValue("route-2").string("server"))
        assertEquals("route-3", box.getValue("route-2").string("detour"))
        assertEquals("192.0.2.30", box.getValue("route-3").string("server"))
    }
}
