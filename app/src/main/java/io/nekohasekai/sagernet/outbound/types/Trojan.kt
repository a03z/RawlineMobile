package app.rawline.mobile.outbound.types

import app.rawline.mobile.R
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.common.Multiplex
import app.rawline.mobile.outbound.common.Tls
import app.rawline.mobile.outbound.common.Transport
import app.rawline.mobile.outbound.common.XrayStreamSetting
import app.rawline.mobile.outbound.common.tlsForTransport
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.ParseDiag
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.validation.Validation
import app.rawline.mobile.validation.addIssue
import app.rawline.mobile.validation.vtr

/** Trojan (include/configs/outbounds/trojan.h, src/configs/outbounds/trojan.cpp). */
class Trojan : Outbound("trojan") {
    @JvmField var password: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var multiplex: Multiplex = Multiplex()
    @JvmField var transport: Transport = Transport()

    override fun hasTls(): Boolean = true
    override fun hasMux(): Boolean = true
    override fun hasTransport(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getMux(): Multiplex = multiplex
    override fun getTransport(): Transport = transport

    // WS and gRPC run on Xray with the authority of their link, as VLESS does (Transport.runsOnXray).
    override fun isXray(): Boolean = transport.runsOnXray(tls)
    override fun getXrayStream(): XrayStreamSetting = XrayStreamSetting().also { it.parseFromLink(exportToLink()) }

    override fun buildXray(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["protocol"] = "trojan"
        val settings = JsonObject()
        settings["address"] = server
        settings["port"] = serverPort
        settings["password"] = password
        obj["settings"] = settings
        val stream = getXrayStream().build(ctx)
        if (!stream.ok) return stream
        if (stream.json.isNotEmpty()) obj["streamSettings"] = stream.json
        return BuildResult(obj)
    }

    /** Trojan::ParseFromLink (trojan.cpp): port 443 by default, TLS is not forced on, always true for a parsable URL. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        super.parseFromLink(url)
        password = url.userName
        if (serverPort == 0) serverPort = 443
        tls.parseFromLink(url)
        if (!tls.enabled && url.query.value("security").trim().isEmpty()) {
            ParseDiag.warn("security", vtr(R.string.validation_trojan_no_tls))
        }
        transport.parseFromLink(url)
        multiplex.parseFromLink(url)
        return true
    }

    /** Trojan::ParseFromClash (trojan.cpp). */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "trojan") return false
        baseParseFromClash(proxy)
        password = proxy.string("password")
        tls.parseFromClash(proxy)
        tls.enabled = true
        transport.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        return true
    }

    /** Trojan::ParseFromJson (trojan.cpp). */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "trojan") return false
        super.parseFromJson(obj)
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("transport")) transport.parseFromJson(obj.obj("transport"))
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** Trojan::ExportToLink (trojan.cpp): an explicit multiplex Off is not written (only an enabled one is), no dial-field items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("trojan")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        // a password missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (password.isNotEmpty()) url.setUserName(password)
        if (tls.enabled) url.addQueryItems(tls.exportToLink())
        if (transport.type.isNotEmpty()) url.addQueryItems(transport.exportToLink(tls.enabled))
        if (multiplex.enabled) url.addQueryItems(multiplex.exportToLink())
        return url.build()
    }

    /** Trojan::ExportToJson (trojan.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "trojan"
        obj.merge(baseExportToJson())
        if (password.isNotEmpty()) obj["password"] = password
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.exportToJson()
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** Trojan::Build (trojan.cpp): the quic transport drops uTLS ([tlsForTransport]). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "trojan"
        obj.merge(baseBuild(ctx))
        if (password.isNotEmpty()) obj["password"] = password
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsForTransport(tlsObj, transport.type)
        val transportObj = transport.build(ctx)
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** Trojan::DisplayType (trojan.cpp). */
    override fun displayType(): String = "Trojan"

    /** Trojan::Validate (trojan.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "password", Validation.required(password))
    }
}
