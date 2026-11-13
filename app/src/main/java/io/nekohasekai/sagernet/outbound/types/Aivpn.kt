package app.rawline.mobile.outbound.types
import app.rawline.mobile.outbound.*
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.bg.carrier.*

/**
 * An AIVPN profile: the original aivpn-client (infosave2007/aivpn) in its SOCKS5 proxy mode, behind the per-launch
 * credentials of Rawline's patch (carriers/aivpn). The connection key (aivpn://…) holds the server and its keys.
 * The same JSON as the desktop: {"type":"aivpn","key","mask","polymorphic","adaptive_level","dns"}.
 */
class Aivpn:Outbound("aivpn"),NativeCarrier {
    override val carrierKind get()=CarrierKind.AIVPN
    @JvmField var key=""
    @JvmField var mask="auto"
    @JvmField var polymorphic=false
    @JvmField var adaptiveLevel=0
    /** --proxy-dns: up to four IPv4 addresses, comma separated; empty keeps the client's own. */
    @JvmField var dns=""
    override fun displayType()="AIVPN"
    override fun displayAddress()=if(server.contains(':')) "[$server]:$serverPort" else "$server:$serverPort"

    companion object {
        /** The masks aivpn-client accepts by name; "auto" leaves the choice to the server. */
        val MASKS=listOf("auto","webrtc_zoom_v3","quic_https_v2","webrtc_yandex_telemost_v1","webrtc_vk_teams_v1",
            "webrtc_sberjazz_v1","telegram_calls_v1","whatsapp_voip_v1","vk_video_v1","sber_salute_v1","avito_api_v1")
        private val IPV4=Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

        /** aivpn-client's own reading of a key: aivpn:// and base64url (no padding) of a JSON object with "s" and "k". */
        fun keyPayload(key:String):JsonObject? {
            val body=key.trim()
            if(!body.startsWith("aivpn://",ignoreCase=true)) return null
            val bytes=Base64Strict.decode(body.substring(8).trimEnd('='),urlSafe=true) ?: return null
            val json=JsonInput.parseObjectOrNull(String(bytes,Charsets.UTF_8)) ?: return null
            return json.takeIf { it.string("s").isNotEmpty() && it.string("k").isNotEmpty() }
        }
    }

    private fun dnsServers()=dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun valid()=keyPayload(key)!=null && mask in MASKS && adaptiveLevel in 0..3 && dnsServers().let { it.size<=4 && it.all(IPV4::matches) }

    override fun parseFromLink(link:String):Boolean {
        val payload=keyPayload(link) ?: return false
        key=link.trim()
        // "s" is host:port or [IPv6]:port.
        val address=payload.string("s")
        val colon=address.lastIndexOf(':')
        server=(if(colon>0) address.substring(0,colon) else address).removePrefix("[").removeSuffix("]")
        serverPort=if(colon>0) address.substring(colon+1).toIntOrNull() ?: 443 else 443
        if(name.isEmpty()) name=server
        return valid()
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="aivpn") return false
        name=obj.string("name").ifEmpty { obj.string("tag") }
        mask=obj.string("mask").ifEmpty { "auto" }
        polymorphic=obj.bool("polymorphic")
        adaptiveLevel=obj.int("adaptive_level")
        dns=obj.string("dns")
        return parseFromLink(obj.string("key"))
    }

    override fun exportToJson()=jsonObjectOf("type" to type,"key" to key,"mask" to mask,"polymorphic" to polymorphic,
        "adaptive_level" to adaptiveLevel,"dns" to dns).also { if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=exportToJson().also { it.remove("name") }
    override fun exportToLink()=key
    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"AIVPN requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=JsonObject().also {
        require(valid())
        // The client's own control socket: every instance needs a loopback port of its own.
        val admin=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { socket -> socket.localPort }
        require(admin!=port)
        it["key"]=key.trim();it["mask"]=mask;it["polymorphic"]=polymorphic;it["adaptive_level"]=adaptiveLevel
        if(dnsServers().isNotEmpty()) it["dns"]=JsonArray.of(*dnsServers().toTypedArray())
        it["socks"]="127.0.0.1:$port";it["admin"]="127.0.0.1:$admin"
        it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
