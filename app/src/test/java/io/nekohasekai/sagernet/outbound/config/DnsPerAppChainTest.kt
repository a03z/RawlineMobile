package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.route.OutboundIds
import app.rawline.mobile.route.RouteProfile
import app.rawline.mobile.route.RouteRule
import app.rawline.mobile.route.RuleSetCatalog
import org.junit.Assert.*
import org.junit.Test

/**
 * DNS for connections routed by an app rule must leave through the same chain the connection goes through:
 * one per-chain clone of the remote resolver (detour = the chain) selected by the rule's packages. Apps' own
 * queries keep answering from fakeip; the split is the core's (dns/router.go allowFakeIP=false for resolves),
 * so the per-package rules sit behind the fakeip rule, not in front of it.
 */
class DnsPerAppChainTest {
    private class Provider(
        private val profiles: Map<Long, Outbound>,
        private val groups: Map<Long, Pair<Long, Long>> = emptyMap(),
    ) : ProfileProvider {
        override fun get(id: Long): Outbound? = profiles[id]
        override fun groupProxies(profileId: Long): Pair<Long, Long> = groups[profileId] ?: (-1L to -1L)
    }

    private fun socks(server: String) = Socks().apply { this.server = server; serverPort = 1080 }

    private fun outbounds(result: GeneratedConfig) =
        JsonInput.parseObject(result.coreConfig).array("outbounds").filterIsInstance<JsonObject>()
            .associateBy { it.string("tag") }

    private fun dns(result: GeneratedConfig) = JsonInput.parseObject(result.coreConfig).obj("dns")

    private fun servers(result: GeneratedConfig) =
        dns(result).array("servers").filterIsInstance<JsonObject>().associateBy { it.string("tag") }

    private fun rules(result: GeneratedConfig) = dns(result).array("rules").filterIsInstance<JsonObject>()

    @Test fun appRuleWithOwnChainResolvesThroughThatChainBehindFakeip() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20"); val front = socks("192.0.2.30")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit, 3L to front)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { package_name.add("eu.app"); action = "route"; outbound_id = 2L })
        }
        val settings = GeneratorSettings(fakeDns = true)
        val result = ConfigGenerator(Provider(profiles, mapOf(2L to (-1L to 3L))), settings,
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)

        val chain = outbounds(result).getValue("route-0")
        assertEquals("route-1", chain.string("detour")) // wrapped in the front proxy
        val remote = servers(result).getValue("dns-remote")
        val perChain = servers(result).getValue("dns-remote-route-0")
        assertEquals(remote.string("server"), perChain.string("server"))   // same resolver transport...
        assertEquals("route-0", perChain.string("detour"))                  // ...dialled through the chain
        assertEquals("dns-local", perChain.string("domain_resolver"))

        val rules = rules(result)
        val fakeip = rules.indexOfFirst { it.string("server") == "dns-fake" }
        val pkg = rules.indexOfFirst { it.string("server") == "dns-remote-route-0" }
        assertTrue("fakeip rule present", fakeip >= 0)
        assertTrue("package rule present", pkg >= 0)
        assertTrue("package rule behind the fakeip rule", pkg > fakeip)
        val rule = rules[pkg]
        assertTrue(rule.array("package_name").contains("eu.app"))
        assertEquals("route", rule.string("action"))
        // The core only fills the package metadata with find_process on.
        assertTrue(JsonInput.parseObject(result.coreConfig).obj("route").bool("find_process"))
    }

    @Test fun directAppRuleResolvesDirectAndProxyAppRuleAddsNothing() {
        val main = socks("192.0.2.10")
        val profiles = mapOf<Long, Outbound>(1L to main)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { package_name.add("mob.app"); action = "route"; outbound_id = OutboundIds.DIRECT })
            rules.add(RouteRule().apply { package_name.add("core.app"); action = "route"; outbound_id = OutboundIds.PROXY })
        }
        val result = ConfigGenerator(Provider(profiles), GeneratorSettings(fakeDns = true),
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)
        val rules = rules(result)
        val direct = rules.filter { it.string("server") == "dns-direct" && it.array("package_name").isNotEmpty() }
        assertEquals(1, direct.size)
        assertTrue(direct[0].array("package_name").contains("mob.app"))
        // proxy is dns-remote's own detour already; a rule naming it would be a no-op duplicate.
        assertTrue(rules.none { it.string("server") == "dns-remote" && it.array("package_name").isNotEmpty() })
        assertFalse(servers(result).containsKey("dns-remote-proxy"))
    }

    @Test fun rulesSharingAChainShareOneResolverAndMergePackagesInRuleOrder() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { package_name.add("a.app"); action = "route"; outbound_id = 2L })
            rules.add(RouteRule().apply { package_name.add("b.app"); action = "route"; outbound_id = 2L })
        }
        val result = ConfigGenerator(Provider(profiles), GeneratorSettings(fakeDns = true),
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)
        assertEquals(1, servers(result).keys.count { it == "dns-remote-route-0" })
        val pkg = rules(result).filter { it.string("server") == "dns-remote-route-0" }
        assertEquals(1, pkg.size)
        assertEquals(listOf<Any>("a.app", "b.app"), pkg[0].array("package_name").strings())
    }

    @Test fun networkScopedAppRuleCarriesItsNetworkType() {
        val main = socks("192.0.2.10"); val exit = socks("192.0.2.20")
        val profiles = mapOf<Long, Outbound>(1L to main, 2L to exit)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply {
                package_name.add("wifi.only"); network_type.add("wifi"); action = "route"; outbound_id = 2L
            })
        }
        val result = ConfigGenerator(Provider(profiles), GeneratorSettings(fakeDns = true),
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)
        val rule = rules(result).first { it.string("server") == "dns-remote-route-0" }
        assertTrue(rule.array("network_type").contains("wifi"))
    }

    @Test fun withoutAppRulesTheDnsSectionStaysAsBefore() {
        val main = socks("192.0.2.10")
        val profiles = mapOf<Long, Outbound>(1L to main)
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { ip_cidr.add("10.0.0.0/8"); action = "route"; outbound_id = -2L })
        }
        val result = ConfigGenerator(Provider(profiles), GeneratorSettings(fakeDns = true),
            routing = RoutingInput(route, RuleSetCatalog(emptyList()))).build(1L)
        assertNull(result.error)
        assertTrue(rules(result).none { it.contains("package_name") })
        assertEquals(setOf("dns-remote", "dns-direct", "dns-fake", "dns-local"), servers(result).keys)
    }
}
