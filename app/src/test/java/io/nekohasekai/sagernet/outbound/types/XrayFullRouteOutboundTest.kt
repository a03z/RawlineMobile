package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayFullRouteOutboundTest {
    private fun full(config: String) = Custom().apply { subtype = Custom.CUSTOM_XRAY_FULL_CONFIG; name = "Turkey"; this.config = config }

    @Test fun proxyOutboundBecomesTheRouteOutbound() {
        val route = full("""{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"vless","settings":{"address":"203.0.113.1","port":443}}],"routing":{"rules":[]}}""").routeOutbound()!!
        assertEquals(Custom.CUSTOM_XRAY_OUTBOUND, route.subtype)
        assertEquals("Turkey", route.name)
        val outbound = JsonInput.parseObject(route.config)
        assertEquals("vless", outbound.string("protocol"))
        assertEquals("", outbound.string("tag"))
    }

    @Test fun firstProxyWhenNoneIsTaggedProxy() {
        val route = full("""{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"main","protocol":"trojan","settings":{}}]}""").routeOutbound()!!
        assertEquals("trojan", JsonInput.parseObject(route.config).string("protocol"))
    }

    @Test fun outboundDialingThroughAnotherIsNotRoutable() {
        assertNull(full("""{"outbounds":[{"tag":"proxy","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"front"}}},{"tag":"front","protocol":"socks"}]}""").routeOutbound())
        assertNull(full("""{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""").routeOutbound())
    }
}
