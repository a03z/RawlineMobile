package app.rawline.mobile.outbound.types

import app.rawline.mobile.R
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.common.Tls
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.ParseDiag
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.validation.addIssue
import app.rawline.mobile.validation.vtr

/** The Checks of http.h. */
object HttpChecks {
    /** The core sends Proxy-Authorization only when a user name is set. */
    @JvmStatic
    fun httpAuth(username: String, password: String): String {
        if (password.isEmpty() || username.isNotEmpty()) return ""
        return vtr(R.string.validation_socks_username_missing)
    }
}

/** http (include/configs/outbounds/http.h, src/configs/outbounds/http.cpp). */
class Http : Outbound("http") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var path: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** http::ParseFromLink (http.cpp). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password
        // v2rayN base64s "user:pass" into the username; a plain name like "user" is valid base64 too
        if (password.isEmpty() && username.isNotEmpty()) {
            val decoded = Base64Strict.decodeToString(username)
            if (decoded.contains(':')) {
                username = QtStrings.substrBefore(decoded, ":")
                password = QtStrings.substrAfter(decoded, ":")
            }
        }
        // some providers set only the password (http://:token@host); the username must equal it
        if (username.isEmpty() && password.isNotEmpty()) username = password
        if (q.has("path")) path = q.value("path")
        val rawHeaders = q.value("headers")
        if (rawHeaders.isNotEmpty()) {
            headers = QtStrings.split(rawHeaders, ",")
            if (headers.size % 2 != 0) {
                ParseDiag.warn("headers", vtr(R.string.validation_http_headers_unpaired))
                headers = ArrayList()
            }
        }
        if (url.scheme == "https" || q.value("security") == "tls") {
            tls.parseFromLink(url)
            tls.enabled = true
        }
        if (serverPort == 0) serverPort = if (tls.enabled) 443 else 80
        return true
    }

    /** http::ParseFromClash (http.cpp). */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "http") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        tls.parseFromClash(node, clashTlsOn(proxy))
        return true
    }

    /** http::ParseFromJson (http.cpp). */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "http") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("path")) path = obj.string("path")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** http::ExportToLink (http.cpp). */
    override fun exportToLink(): String {
        val url = LinkBuilder(if (tls.enabled) "https" else "http")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (path.isNotEmpty()) url.addQueryItem("path", path)
        if (headers.isNotEmpty()) url.addQueryItem("headers", headers.joinToString(","))
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** http::ExportToJson (http.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "http"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (path.isNotEmpty()) obj["path"] = path
        val headerObj = JsonValues.pairListToObject(headers)
        if (headerObj.isNotEmpty()) obj["headers"] = headerObj
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        return obj
    }

    /** http::Build (http.cpp). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "http"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (path.isNotEmpty()) obj["path"] = path
        val headerObj = JsonValues.pairListToObject(headers)
        if (headerObj.isNotEmpty()) obj["headers"] = headerObj
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        return BuildResult(obj)
    }

    /** http::DisplayType (http.cpp). */
    override fun displayType(): String = if (tls.enabled) "HTTPS" else "HTTP"

    /** http::Validate (http.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Warning, "password", HttpChecks.httpAuth(username, password))
    }
}
