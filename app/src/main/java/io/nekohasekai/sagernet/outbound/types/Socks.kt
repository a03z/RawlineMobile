package app.rawline.mobile.outbound.types

import app.rawline.mobile.R
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.link.Hosts
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkCodec
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.ParseDiag
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.validation.addIssue
import app.rawline.mobile.validation.vtr

/** The Checks of socks.h. */
object SocksChecks {
    /** SOCKS4 sends only a user ID, and SOCKS5 sends the password only together with a user name. */
    @JvmStatic
    fun socksAuth(version: Int, username: String, password: String): String {
        if (password.isEmpty()) return ""
        if (version == 4) return vtr(R.string.validation_socks_v4_password)
        if (username.isEmpty()) return vtr(R.string.validation_socks_username_missing)
        return ""
    }
}

/** socks (include/configs/outbounds/socks.h, src/configs/outbounds/socks.cpp). */
class Socks : Outbound("socks") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var version: Int = 5
    /** With version 4: SOCKS4a, the server resolves host names (plain SOCKS4 makes the core resolve them). */
    @JvmField var socks4a: Boolean = false
    @JvmField var uot: Boolean = false

    /** Phobos SOCKS5 mode: the SOCKS5 server sits behind the obfuscator's client role; an empty key is plain SOCKS. */
    @JvmField var phobos_key: String = ""
    /** NONE, STUN, MEDIA or TLS. */
    @JvmField var phobos_masking: String = ""
    /** Decimal, empty when the SSRC is random. */
    @JvmField var phobos_media_ssrc: String = ""

    /** The [instance] (mode = socks5) and [socks5] sections of a Phobos .conf. */
    fun parsePhobosConf(conf: String): Boolean {
        var section = ""
        var target = ""
        for (line in conf.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                continue
            }
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            val key = trimmed.substring(0, equals).trim().lowercase()
            val value = trimmed.substring(equals + 1).trim()
            if (value.equals("none", ignoreCase = true)) continue
            if (section == "instance") when (key) {
                "target" -> target = value
                "key" -> phobos_key = value
                "masking" -> phobos_masking = value.uppercase().takeIf { it != "AUTO" }.orEmpty()
                "media-ssrc" -> phobos_media_ssrc = WireGuard.ssrcText(value)
            } else if (section == "socks5") when (key) {
                "login" -> username = value
                "password" -> password = value
            }
        }
        val colon = target.lastIndexOf(':')
        if (colon <= 0 || phobos_key.isEmpty()) return false
        server = target.substring(0, colon).trim().removeSurrounding("[", "]")
        serverPort = QtStrings.toInt(target.substring(colon + 1))
        version = 5
        return server.isNotEmpty() && serverPort in 1..65535
    }

    /** parseSocksVersion (socks.cpp): build emits SOCKS5 for anything but 4, which is also the core's default. */
    private fun parseVersion(text: String) {
        val value = text.trim().lowercase()
        socks4a = value == "4a"
        version = if (socks4a || value == "4") 4 else 5
        if (value.isNotEmpty() && version == 5 && value != "5") {
            ParseDiag.warn("version", vtr(R.string.validation_socks_version_unknown), text.trim())
        }
    }

    /** socks::ParseFromLink (socks.cpp). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("version")) {
            parseVersion(q.value("version"))
        } else {
            if (url.scheme == "socks4" || url.scheme == "socks4a") version = 4
            if (url.scheme == "socks5") version = 5
            socks4a = url.scheme == "socks4a"
        }
        if (url.password.isNotEmpty() || url.userName.isNotEmpty()) {
            username = url.userName
            password = url.password
            // v2rayN base64-encodes "user:pass" into the username; a plain name like "user" is valid base64 too
            if (password.isEmpty() && username.isNotEmpty()) {
                val decoded = Base64Strict.decodeToString(username)
                if (decoded.contains(':')) {
                    username = QtStrings.substrBefore(decoded, ":")
                    password = QtStrings.substrAfter(decoded, ":")
                }
            }
        }
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        if (serverPort == 0) serverPort = 1080
        return server.isNotEmpty()
    }

    /** socks::ParseFromClash (socks.cpp). */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "socks5") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        return true
    }

    /** socks::ParseFromJson (socks.cpp): `version` is a number or the "4" / "4a" text [exportToJson] writes. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "socks") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("version")) parseVersion(obj.variantString("version"))
        if (obj.contains("uot")) uot = obj.bool("uot")
        phobos_key = obj.string("phobos_key")
        phobos_masking = obj.string("phobos_masking")
        phobos_media_ssrc = WireGuard.ssrcText(JsonValues.toInteger(obj["phobos_media_ssrc"]).toString())
        if (obj.contains("udp_over_tcp")) {
            if (obj.isBool("udp_over_tcp")) uot = obj.bool("udp_over_tcp")
            if (obj.isObject("udp_over_tcp")) uot = obj.obj("udp_over_tcp").bool("enabled")
        }
        return true
    }

    /** socks::ExportToLink (socks.cpp). */
    override fun exportToLink(): String {
        if (phobos_key.isNotEmpty()) {
            val conf = arrayListOf("[instance]", "mode = socks5", "role = client", "source-if = 127.0.0.1", "source-lport = 1080",
                "target = " + Hosts.displayAddress(server, serverPort), "key = $phobos_key", "masking = " + phobos_masking.ifEmpty { "NONE" })
            if (phobos_media_ssrc.isNotEmpty()) conf += "media-ssrc = $phobos_media_ssrc"
            conf += listOf("", "[socks5]", "login = " + username.ifEmpty { "none" }, "password = " + password.ifEmpty { "none" })
            return "phobos://" + Base64Strict.encode((conf.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8), urlSafe = true, padding = false) +
                "#" + (if (name.isEmpty()) "none" else LinkCodec.percentEncodeAll(name))
        }
        val url = LinkBuilder(if (version != 4) "socks5" else if (socks4a) "socks4a" else "socks4")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** socks::ExportToJson (socks.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = if (socks4a) "4a" else "4"
        if (uot) obj["uot"] = uot
        if (phobos_key.isNotEmpty()) {
            obj["phobos_key"] = phobos_key
            if (phobos_masking.isNotEmpty()) obj["phobos_masking"] = phobos_masking
            if (phobos_media_ssrc.isNotEmpty()) obj["phobos_media_ssrc"] = WireGuard.parseSsrc(phobos_media_ssrc)
        }
        return obj
    }

    /** socks::Build (socks.cpp): the core's UoT option is udp_over_tcp. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        if (phobos_key.isNotEmpty()) {
            // The core's Phobos outbound: SOCKS5 inside the obfuscated stream, TCP only.
            obj["type"] = "phobos"
            obj.merge(baseBuild(ctx))
            if (username.isNotEmpty()) obj["username"] = username
            if (password.isNotEmpty()) obj["password"] = password
            obj["key"] = phobos_key
            if (phobos_masking.isNotEmpty() && phobos_masking != "NONE") obj["masking"] = phobos_masking.lowercase()
            if (WireGuard.parseSsrc(phobos_media_ssrc) > 0) obj["media_ssrc"] = WireGuard.parseSsrc(phobos_media_ssrc)
            return BuildResult(obj)
        }
        obj["type"] = "socks"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = if (socks4a) "4a" else "4"
        if (uot) obj["udp_over_tcp"] = true
        return BuildResult(obj)
    }

    /** socks.cpp:115-118. */
    override fun displayType(): String = if (phobos_key.isEmpty()) "Socks" else "Phobos"

    /** socks::Validate (socks.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Warning, "password", SocksChecks.socksAuth(version, username, password))
    }
}
