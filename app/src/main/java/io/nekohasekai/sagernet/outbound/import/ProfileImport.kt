package app.rawline.mobile.outbound.import

import app.rawline.mobile.group.ProviderLinks
import app.rawline.mobile.group.SubscriptionCrypto
import app.rawline.mobile.outbound.types.DnsTunnel
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.R
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.outbound.common.DnsTunnels
import app.rawline.mobile.outbound.common.TLS_UNSAFE_FINGERPRINT
import app.rawline.mobile.outbound.common.stripXrayDialerState
import app.rawline.mobile.outbound.common.xrayDownloadLeg
import app.rawline.mobile.outbound.common.xrayNetworks
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonWriter
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.link.LinkCodec
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.outbound.types.Custom
import app.rawline.mobile.outbound.types.OpenConnect
import app.rawline.mobile.outbound.types.OpenVpn
import app.rawline.mobile.outbound.types.Shadowsocks
import app.rawline.mobile.outbound.types.WireGuard
import app.rawline.mobile.outbound.types.XrayVless
import app.rawline.mobile.ui.json.engine.JsonTree
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.ParseDiag
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.validation.Validation
import app.rawline.mobile.validation.vplural
import app.rawline.mobile.validation.vtr
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The desktop's subscription / clipboard / file parser (src/configs/sub/SubscriptionParser.cpp with the scanning
 * helpers of include/configs/sub/SubscriptionScan.hpp). [parse] is ParseText: the whole body is base64-decoded
 * when it looks like one blob (standard, then a wrapped standard blob, then the url-safe alphabet), then the
 * document is classified in the desktop's order: JSON (Xray outbounds / configs, sing-box outbounds / endpoints,
 * SIP008), Clash YAML, a WireGuard INI file, an OpenVPN profile, an OpenConnect profile, and finally one item per
 * line (a line opening a bracket yields its balanced JSON block), each of which may itself be base64 or a share
 * link. Every parsed profile goes through stripForeignState and validate: an Error issue refuses it, and every item
 * that was refused or carries issues leaves a [ParseReport].
 */
object ProfileImport {

    /** The profiles produced and one [ParseReport] per item that was refused or carries issues. */
    class Result(@JvmField val outbounds: List<Outbound>, @JvmField val reports: List<ParseReport>) {
        /** The reports as log lines, "source: [error] field: message". */
        val messages: List<String>
            get() = reports.flatMap { report ->
                report.issues.map { issue ->
                    val tag = if (issue.severity == Severity.Error) "[error]" else "[warning]"
                    val text = if (issue.field.isEmpty()) issue.message else issue.field + ": " + issue.message
                    if (report.source.isEmpty()) "$tag $text" else "${report.source}: $tag $text"
                }
            }

        /** The aggregate for the import box; the caller sets the stored count with [ImportReport.setImported]. */
        fun report(): ImportReport = ImportReport().also { it.addAll(reports) }
    }

    private const val MAX_DEPTH = 16

    /** The size cap of a compressed vpn:// payload (SubscriptionParser.cpp). */
    private const val MAX_VPN_PAYLOAD = 16L * 1024 * 1024

    /** [ctx] carries the global settings validate reads (SettingsMapper.buildContext()). */
    @JvmStatic
    @JvmOverloads
    fun parse(
        text: String,
        preference: OutboundFactory.XrayVlessPreference = OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE,
        ctx: BuildContext = BuildContext.DEFAULT,
    ): Result {
        var body = Scan.trim(text)
        if (body.isEmpty()) return Result(emptyList(), emptyList())
        if (Scan.looksLikeBase64(body)) {
            Scan.decodeBase64(body)?.let { body = it }
        } else if (Scan.looksLikeWrappedBase64(body)) {
            Scan.decodeBase64Lenient(body)?.let { body = it }
        } else if (Scan.looksLikeUrlSafeBase64(body)) {
            Scan.decodeBase64UrlSafe(body)?.let { body = it }
        }
        val parser = Parser(preference, ctx)
        parser.document(body, allowBase64 = false, needParse = true, depth = 0)
        return Result(parser.produced, parser.reports)
    }

    /** ShippedRoutingLink (SubscriptionParser.cpp:1223): the first `<scheme>://routing/...` line of a body, plain or base64; "" when none. */
    fun shippedRoutingLink(text: String): String {
        var body = Scan.trim(text)
        if (body.isEmpty()) return ""
        if (Scan.looksLikeBase64(body)) {
            Scan.decodeBase64(body)?.let { body = it }
        } else if (Scan.looksLikeWrappedBase64(body)) {
            Scan.decodeBase64Lenient(body)?.let { body = it }
        } else if (Scan.looksLikeUrlSafeBase64(body)) {
            Scan.decodeBase64UrlSafe(body)?.let { body = it }
        }
        var found = ""
        Scan.forEachLine(body) { raw ->
            val line = Scan.trim(raw)
            if (!isRoutingLink(line)) return@forEachLine true
            found = line
            false
        }
        return found
    }

    /** "happ://routing/onadd/...": a routing profile for the group, read by [shippedRoutingLink], not a proxy. */
    fun isRoutingLink(line: String): Boolean {
        val scheme = linkScheme(line)
        return scheme.isNotEmpty() && line.regionMatches(scheme.length, "://routing/", 0, 11, ignoreCase = true)
    }

    private enum class SingBoxSubType { OutboundInJson, OutboundJsonArray, OutboundObject, Invalid }
    private enum class XraySubType { OutboundInJson, OutboundJsonArray, OutboundObject, ConfigJsonArray, Invalid }

    private enum class XrayValue { Flag, Text, TextOrList, Object }

    /** XrayKey: [modelled] the VLESS model reads this exact spelling; [protects] losing the value weakens the connection. */
    private class XrayKey(val name: String, val value: XrayValue, val modelled: Boolean, val protects: Boolean)

    /** XrayUnmodelledKey: [spelling] is the one the VLESS model reads, empty when it cannot store the key at all. */
    private class XrayUnmodelledKey(val path: String, val spelling: String, val protects: Boolean)

    private fun parserError(message: String, field: String = "") = Issue(Severity.Error, field, message)

    private fun parserWarning(message: String, field: String = "") = Issue(Severity.Warning, field, message)

    private fun elide(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length > 64) trimmed.take(64) + "…" else trimmed
    }

    /** Non-proxy entries every full config carries; skipping them is not worth a report. */
    private fun isSingBoxInfraType(type: String) =
        type == "direct" || type == "block" || type == "dns" || type == "selector" || type == "urltest"

    private val CLASH_INFRA_TYPES = setOf(
        "direct", "reject", "reject-drop", "dns", "pass", "compatible", "relay", "select", "url-test", "fallback", "load-balance",
    )

    private fun isXrayInfraProtocol(protocol: String) = protocol == "freedom" || protocol == "direct" ||
        protocol == "blackhole" || protocol == "block" || protocol == "dns" || protocol == "loopback"

    /** INI sections ("[Interface]", "[General]") also open with '['; JSON arrays hold values, not bare words. */
    private fun looksLikeJson(text: String): Boolean {
        if (text[0] == '{') return true
        val rest = Scan.trim(text.substring(1))
        if (rest.isEmpty()) return false
        val c = rest[0]
        return c == '{' || c == '[' || c == '"' || c == ']' || c == '-' || c in '0'..'9'
    }

    /** "ssr" for "ssr://...": the RFC 3986 scheme grammar, so plain text and "#key: value" metadata never qualify. */
    private fun linkScheme(line: String): String {
        val sep = line.indexOf("://")
        if (sep <= 0 || !(line[0] in 'a'..'z' || line[0] in 'A'..'Z')) return ""
        for (i in 1 until sep) {
            val c = line[i]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '.' || c == '-')) return ""
        }
        return line.substring(0, sep)
    }

    /** Never the raw link: "scheme://host:port #name", the address taken from the parsed outbound when it has one. */
    private fun linkSource(link: String, ob: Outbound?): String {
        val sep = link.indexOf("://")
        val label = StringBuilder(if (sep > 0) link.substring(0, sep).lowercase() + "://" else "")
        var host = ob?.server?.trim().orEmpty()
        var port = ob?.serverPort ?: 0
        var name = ob?.name.orEmpty()
        if (host.isEmpty() || name.isBlank()) {
            val url = LinkParser.parse(link)
            // Base64 bodies (vmess://, legacy ss://) parse as a host; only a host:port authority is an address.
            if (host.isEmpty() && url.port > 0 && url.host.isNotEmpty()) {
                host = url.host
                port = url.port
            }
            if (name.isBlank()) name = url.fragment.orEmpty()
        }
        if (host.isNotEmpty()) {
            label.append(if (host.contains(':')) "[$host]" else host)
            if (port > 0) label.append(':').append(port)
        }
        val shown = elide(name)
        if (shown.isNotEmpty()) label.append(" #").append(shown)
        return label.toString()
    }

    private fun singBoxSource(endpoint: Boolean, tag: String, index: Int): String {
        val name = elide(tag)
        return if (endpoint) {
            if (name.isEmpty()) vtr(R.string.validation_import_singbox_endpoint_index, index.toString())
            else vtr(R.string.validation_import_singbox_endpoint_named, name)
        } else {
            if (name.isEmpty()) vtr(R.string.validation_import_singbox_outbound_index, index.toString())
            else vtr(R.string.validation_import_singbox_outbound_named, name)
        }
    }

    private fun xraySource(tag: String, index: Int): String {
        val name = elide(tag)
        return if (name.isEmpty()) vtr(R.string.validation_import_xray_outbound_index, index.toString())
        else vtr(R.string.validation_import_xray_outbound_named, name)
    }

    private fun xrayConfigSource(remarks: String, index: Int): String {
        val name = elide(remarks)
        return if (name.isEmpty()) vtr(R.string.validation_import_xray_config_index, index.toString())
        else vtr(R.string.validation_import_xray_config_named, name)
    }

    private fun clashSource(proxyName: String, index: Int): String {
        val name = elide(proxyName)
        return if (name.isEmpty()) vtr(R.string.validation_import_clash_proxy_index, index.toString())
        else vtr(R.string.validation_import_clash_proxy_named, name)
    }

    private fun namedFileSource(kind: String, profileName: String): String {
        val name = elide(profileName)
        return if (name.isEmpty()) kind else "$kind \"$name\""
    }

    /** The importers' notes; when the import failed, the last note is the reason. */
    private fun appendImporterNotes(issues: MutableList<Issue>, notes: List<String>, ok: Boolean) {
        for ((i, note) in notes.withIndex()) {
            issues.add(if (!ok && i == notes.size - 1) parserError(note) else parserWarning(note))
        }
    }

    private fun firstObject(arr: JsonArray): JsonObject = if (arr.isEmpty()) JsonObject() else arr[0] as? JsonObject ?: JsonObject()

    /** normalizeXrayVlessForParse's notes (SubscriptionParser.cpp): only the first server and user are imported. */
    private fun xrayVlessNotes(out: JsonObject, notes: MutableList<Issue>) {
        val settings = out.obj("settings")
        if (settings.contains("address") && !settings.contains("vnext")) return
        val vnext = settings.array("vnext")
        if (vnext.isEmpty()) return
        val first = vnext[0] as? JsonObject ?: return
        if (first.isEmpty()) return
        val users = first.array("users")
        if (users.isEmpty()) return
        if (vnext.size > 1) notes.add(parserWarning(vplural(R.plurals.validation_import_first_server, vnext.size), "vnext"))
        if (users.size > 1) notes.add(parserWarning(vplural(R.plurals.validation_import_first_user, users.size), "users"))
    }

    /** A value of the wrong type makes Xray's decoder refuse the whole outbound, so it does not count. */
    private fun xrayValueSet(value: Any?, kind: XrayValue): Boolean {
        val text = value is String && value.isNotBlank()
        return when (kind) {
            XrayValue.Flag -> value == true
            XrayValue.Text -> text
            XrayValue.TextOrList -> text || (value is JsonArray && value.isNotEmpty())
            XrayValue.Object -> value is JsonObject && value.isNotEmpty()
        }
    }

    private fun xrayCollectUnmodelled(obj: JsonObject, at: String, keys: List<XrayKey>, found: MutableList<XrayUnmodelledKey>) {
        for (k in obj.sortedKeys()) {
            for (key in keys) {
                if (!k.equals(key.name, ignoreCase = true)) continue
                if ((!key.modelled || k != key.name) && xrayValueSet(obj[k], key.value)) {
                    found.add(XrayUnmodelledKey(at + k, if (key.modelled) key.name else "", key.protects))
                }
                break
            }
        }
    }

    /** xrayUnmodelledKeys (SubscriptionParser.cpp): set security keys Xray reads that the typed VLESS model would drop. */
    private fun xrayUnmodelledKeys(out: JsonObject): List<XrayUnmodelledKey> {
        val v = XrayValue.Text
        val found = ArrayList<XrayUnmodelledKey>()
        xrayCollectUnmodelled(out, "", listOf(XrayKey("streamSettings", XrayValue.Object, true, true)), found)
        val stream = out.obj("streamSettings")
        val at = "streamSettings."
        xrayCollectUnmodelled(stream, at, listOf(XrayKey("security", v, true, true)), found)
        val security = stream.string("security").trim().lowercase()
        // Xray reads only the block its security selects, which a security spelled in another case makes unknown.
        var securityCased = false
        for (k in stream.keys()) {
            if (k == "security" || !k.equals("security", ignoreCase = true)) continue
            securityCased = securityCased || xrayValueSet(stream[k], v)
        }
        if (securityCased || security == "tls") {
            xrayCollectUnmodelled(stream, at, listOf(XrayKey("tlsSettings", XrayValue.Object, true, true)), found)
        }
        if (securityCased || security == "reality") {
            xrayCollectUnmodelled(stream, at, listOf(XrayKey("realitySettings", XrayValue.Object, true, true)), found)
        }
        if (security == "tls") {
            xrayCollectUnmodelled(
                stream.obj("tlsSettings"), at + "tlsSettings.", listOf(
                    XrayKey("serverName", v, true, false), XrayKey("pinnedPeerCertSha256", v, true, true),
                    XrayKey("verifyPeerCertByName", v, true, true), XrayKey("alpn", XrayValue.TextOrList, true, false),
                    XrayKey("fingerprint", v, true, false), XrayKey("cipherSuites", v, true, false),
                    XrayKey("echConfigList", v, true, true), XrayKey("useSystemCA", XrayValue.Flag, true, false),
                    XrayKey("allowInsecure", XrayValue.Flag, false, false),
                    XrayKey("minVersion", v, false, false), XrayKey("maxVersion", v, false, false),
                    XrayKey("curvePreferences", XrayValue.TextOrList, false, false),
                ), found,
            )
        } else if (security == "reality") {
            xrayCollectUnmodelled(
                stream.obj("realitySettings"), at + "realitySettings.", listOf(
                    XrayKey("serverName", v, true, false), XrayKey("fingerprint", v, true, false),
                    XrayKey("password", v, true, true), XrayKey("publicKey", v, true, true), XrayKey("shortId", v, true, true),
                    XrayKey("spiderX", v, true, false), XrayKey("mldsa65Verify", v, true, true),
                ), found,
            )
        }
        return found
    }

    private fun xrayUnmodelledIssue(key: XrayUnmodelledKey): Issue = when {
        key.spelling.isEmpty() -> parserWarning(vtr(R.string.validation_import_xray_unstorable), key.path)
        key.protects -> parserError(vtr(R.string.validation_import_xray_rename_protects, key.spelling), key.path)
        else -> parserWarning(vtr(R.string.validation_import_xray_rename, key.spelling), key.path)
    }

    /** xrayTypedRepaired: values the typed parse fixes without a note; a verbatim copy would hand Xray the originals, which it refuses. */
    private fun xrayTypedRepaired(out: JsonObject, typed: XrayVless): Boolean {
        fun differs(obj: JsonObject, key: String, kept: String) = obj.string(key) != kept
        val settings = out.obj("settings")
        val user = if (settings.contains("vnext")) firstObject(firstObject(settings.array("vnext")).array("users")) else settings
        if (differs(user, "flow", typed.flow) || differs(user, "encryption", typed.encryption)) return true
        val stream = out.obj("streamSettings")
        val s = typed.streamSetting
        if (stream.contains("security") && stream.string("security").lowercase() != s.security) return true
        // Old mask names and xdns shapes are upgraded, and a legacy mKCP header or seed becomes masks.
        if (s.finalmask != stream.obj("finalmask")) return true
        // The typed profile keeps neither key in any spelling, while Xray reads every spelling of sockopt.
        for (sk in stream.keys()) {
            if (!sk.equals("sockopt", ignoreCase = true)) continue
            val sockopt = stream[sk] as? JsonObject ?: continue
            for (k in sockopt.keys()) {
                if (k.equals("dialerProxy", ignoreCase = true) || k.equals("domainStrategy", ignoreCase = true)) return true
            }
        }
        // stripForeignState drops them from the download leg too.
        for (leg in xrayDownloadLeg(out)) {
            if (stripXrayDialerState(leg.copy())) return true
        }
        if (s.security == "tls") return differs(stream.obj("tlsSettings"), "fingerprint", s.tls.fingerprint)
        if (s.security != "reality") return false
        val reality = stream.obj("realitySettings")
        val password = reality.string("password")
        // Xray takes any non-empty password over publicKey, so a blank one fails there.
        if (password.isNotEmpty() && password.isBlank()) return true
        val keyField = if (password.isBlank()) "publicKey" else "password"
        // XrayReality.build drops "unsafe", which Xray refuses for REALITY.
        return s.reality.fingerprint.lowercase() == TLS_UNSAFE_FINGERPRINT || differs(reality, "fingerprint", s.reality.fingerprint) ||
            differs(reality, keyField, s.reality.password) || differs(reality, "shortId", s.reality.shortId) ||
            differs(reality, "mldsa65Verify", s.reality.mldsa65Verify)
    }

    private class Parser(private val preference: OutboundFactory.XrayVlessPreference, private val ctx: BuildContext) {
        val produced = ArrayList<Outbound>()
        val reports = ArrayList<ParseReport>()

        /** The address lines of the text being read, for the DNS tunnels in it. */
        private var resolvers = emptyList<String>()

        /** A note about the document from the readers of formats the desktop does not have. */
        private fun log(message: String) = reportDocument("", listOf(parserWarning(message)))

        /** Those readers' profiles go through the same checks as every other. */
        private fun produce(outbound: Outbound?) {
            accept(outbound, outbound != null, ArrayList()) { outbound?.displayTypeAndName().orEmpty() }
        }

        /** [source] is a function: labels are only built for items that have something to report. */
        private fun report(source: () -> String, imported: Boolean, issues: List<Issue>) {
            // A view such as ClashProxy re-reads a key for each block that uses it: one bad value, one issue.
            reports.add(ParseReport(source(), imported, issues.distinct()))
        }

        /** About the whole document rather than one profile: its errors become the import's reasons. */
        private fun reportDocument(source: String, issues: List<Issue>) {
            reports.add(ParseReport(source, false, issues.distinct(), document = true))
        }

        /**
         * Parser::accept: the import checks after a parse; an Error refuses the profile, warnings travel with it.
         * Android also refuses what the core it would run in cannot run (#63, [CoreSupport]).
         */
        private fun accept(outbound: Outbound?, parsed: Boolean, issues: MutableList<Issue>, source: () -> String) {
            var imported = false
            if (outbound == null || !parsed) {
                if (!Validation.hasError(issues)) issues.add(parserError(vtr(R.string.validation_import_unparsed)))
            } else {
                try {
                    outbound.stripForeignState(issues)
                    outbound.validate(issues, ctx)
                } catch (e: RuntimeException) {
                    issues.add(parserError(e.message ?: e.javaClass.simpleName))
                }
                CoreSupport.problem(outbound)?.let { issues.add(parserError(it)) }
                imported = !Validation.hasError(issues)
            }
            if (!imported || issues.isNotEmpty()) report(source, imported, issues)
            if (imported) {
                (outbound as? DnsTunnel)?.adoptResolvers(resolvers)
                produced.add(outbound!!)
            }
        }

        /** [ParseDiag.scoped], with an unexpected exception of [parse] reported as this item's error instead of aborting the import. */
        private inline fun guarded(issues: MutableList<Issue>, parse: () -> Boolean): Boolean = try {
            ParseDiag.scoped(issues, parse)
        } catch (e: Exception) {
            issues.add(parserError(unreadable(e)))
            false
        }

        private fun unreadable(e: Exception): String =
            e.message?.takeIf { it.isNotBlank() }?.let { vtr(R.string.validation_import_clash_unreadable_reason, it) }
                ?: vtr(R.string.validation_import_clash_unreadable)

        /** Parser::document (SubscriptionParser.cpp); [overrideName] names a WireGuard profile that has none. */
        fun document(raw: String, allowBase64: Boolean, needParse: Boolean, depth: Int, overrideName: String = "") {
            if (depth > MAX_DEPTH) return
            val text = Scan.trim(raw)
            if (text.isEmpty()) return

            if (allowBase64) {
                val decoded = when {
                    Scan.looksLikeBase64(text) -> Scan.decodeBase64(text)
                    Scan.looksLikeUrlSafeBase64(text) -> Scan.decodeBase64UrlSafe(text)
                    else -> null
                }
                if (decoded != null) {
                    document(decoded, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = overrideName)
                    return
                }
            }

            if (text[0] == '{' || text[0] == '[') {
                // QJsonDocument::fromJson rejects trailing content, which org.json would silently ignore
                if (Scan.matchingClose(text, 0) == text.length - 1) {
                    val value = JsonInput.parseValue(text)
                    if (value is JsonObject || value is JsonArray) {
                        json(value, text)
                        return
                    }
                }
                // Whole documents only: the per-line split of a broken one would repeat the error for every brace line.
                if (needParse && looksLikeJson(text)) {
                    val before = produced.size
                    val reportedBefore = reports.size
                    documentBody(text, needParse, depth, overrideName)
                    if (produced.size == before && reports.size == reportedBefore) reportJsonSyntax(text)
                    return
                }
            }

            documentBody(text, needParse, depth, overrideName)
        }

        private fun documentBody(text: String, needParse: Boolean, depth: Int, overrideName: String) {
            // CSQTT's /sub endpoint serves the same QR landing page to apps and browsers.
            if (text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) {
                val links = CSQTT_HTML_LINK.findAll(text).map { it.groupValues[1].replace("&amp;", "&") }.distinct()
                for (item in links) link(item, depth + 1, overrideName)
                return
            }

            if (ProviderLinks.isProviderLink(text)) {
                val link = runCatching { ProviderLinks.decodeChecked(text) }.getOrNull()
                if (link == null) log("Cannot decode encrypted provider configuration")
                else if (!SubscriptionCrypto.isHttpUrl(link.data)) {
                    document(link.data, allowBase64 = true, needParse = true, depth = depth + 1, overrideName = overrideName)
                } else log("Subscription URL requires subscription import")
                return
            }

            // QJsonDocument::fromJson rejects trailing content, which org.json would silently ignore
            if ((text[0] == '{' || text[0] == '[') && Scan.matchingClose(text, 0) == text.length - 1) {
                val value = JsonInput.parseValue(text)
                if (value is JsonObject || value is JsonArray) {
                    json(value, text)
                    return
                }
            }

            if (text.contains("proxies:")) {
                clash(text)
                return
            }

            if (hasWireGuardSections(text)) {
                wireguardFile(text, overrideName)
                return
            }

            if (text.contains("ENCRYPTION_KEY") && text.contains("DOMAINS")) {
                MasterDnsFiles.fromToml(text)?.let { config ->
                    produce(OutboundFactory.newByType("masterdns").takeIf { it.parseFromJson(config) })
                    return
                }
            }

            if (looksLikeOvpnConfig(text)) {
                openVpnFile(text)
                return
            }

            if (looksLikeOpenConnectProfile(text)) {
                openConnectProfile(text)
                return
            }

            if (needParse && text.contains('\n')) {
                val outer = resolvers
                val found = ArrayList<String>()
                Scan.forEachLine(text) { line -> found.addAll(DnsTunnel.resolverList(line)); true }
                resolvers = found.distinct().ifEmpty { outer }
                Scan.forEachItem(text) { item ->
                    if (DnsTunnel.resolverList(item).isEmpty()) document(item, allowBase64 = true, needParse = false, depth = depth + 1, overrideName = overrideName)
                }
                resolvers = outer
                return
            }

            link(text, depth, overrideName)
        }

        private fun reportJsonSyntax(text: String) {
            val result = JsonTree.parse(text)
            if (result.ok) return
            val lineColumn = JsonTree.lineColumn(text, result.errorSpan.offset)
            val message = vtr(R.string.validation_import_json_syntax, result.error, lineColumn[0].toString(), lineColumn[1].toString())
            reportDocument("", listOf(parserError(message)))
        }

        /** Parser::json (SubscriptionParser.cpp): Xray first, its configs share the `outbounds` wrapper with sing-box. */
        private fun json(doc: Any, text: String, jsonDepth: Int = 0) {
            if(jsonDepth>MAX_DEPTH) { log("JSON profile nesting limit exceeded");return }
            // Process heterogeneous arrays independently; one native transport must not hide its neighbours.
            if (doc is JsonArray) {
                for (item in doc) {
                    // As on the desktop, each Xray config in an array is self-contained and runs verbatim.
                    if (item is JsonObject && xraySubType(item) == XraySubType.OutboundInJson) Custom.fromXrayFullConfig(item)?.let(::produce)
                    else if (item is JsonObject) json(item, item.toCompact(),jsonDepth+1) else log("Invalid profile JSON item")
                }
                return
            }
            if (doc is JsonObject) {
                val items = when {
                    doc.isArray("configs") -> doc.array("configs")
                    doc.isArray("profiles") -> doc.array("profiles")
                    doc.isArray("servers") && (!doc.contains("version") || doc.array("servers").filterIsInstance<JsonObject>().any {
                        it.contains("type") || it.contains("protocol") || it.contains("peer") || it.contains("outbounds")
                    }) -> doc.array("servers")
                    else -> null
                }
                if (items != null) { json(items, items.toCompact(),jsonDepth+1); return }
                // The S3 Bypass app's configuration: Xray with the fedarisha outbound our Xray core does not have.
                if (doc.array("outbounds").any { it is JsonObject && it.string("protocol")=="fedarisha" }) {
                    val profile=app.rawline.mobile.outbound.types.S3Bypass.fromXrayConfig(doc)
                    if(profile!=null) produce(profile) else log("Invalid S3 Bypass configuration")
                    return
                }
                // Like the desktop: an Xray config with its own routing, DNS or balancing runs verbatim,
                // a bare outbounds list becomes one profile per outbound.
                if (doc.contains("outbounds") && hasXrayOutbound(doc.array("outbounds")) &&
                        listOf("routing","dns","observatory","burstObservatory","policy","reverse","fakedns").any(doc::contains)) {
                    produce(Custom.fromXrayFullConfig(doc)); return
                }
                // A sing-box config becomes one profile per outbound and endpoint, as on the desktop:
                // its route and groups would replace the app's own routing.
                if (doc.string("schema") == "whitedns.profile") {
                    val tunnel=DnsTunnel()
                    if(tunnel.parseWhiteDnsProfile(doc)) produce(tunnel) else log("Invalid DNS tunnel profile")
                    return
                }
                MasterDnsFiles.fromJson(doc)?.let { config ->
                    val outbound=OutboundFactory.newByType("masterdns")
                    if(outbound.parseFromJson(config)) produce(outbound) else log("Invalid MasterDNS profile")
                    return
                }
                val type=doc.string("type").lowercase()
                if(type.isEmpty() && doc.contains("server") && doc.contains("method") && (doc.contains("obfs") || doc.contains("protocol"))) {
                    val copy=doc.copy();copy["type"]="shadowsocksr"
                    val outbound=OutboundFactory.newByType("shadowsocksr")
                    if(outbound.parseFromJson(copy)) produce(outbound) else log("Invalid ShadowsocksR profile")
                    return
                }
                if (type in listOf("qwdtt","wdtt","csqtt","mrtt","wdttplus","freeturn","openflux","openfluxkey","olcrtc","dnstt","vaydns","slipstream","masterdns","cottendns","soar","s3bypass","sstp",
                        "chain","autoselector","custom","xrayvless","ssr","shadowsocksr")) {
                    val outbound=OutboundFactory.newByType(type)
                    if(outbound.parseFromJson(doc)) produce(outbound) else log("Invalid $type profile")
                    return
                }
                if (type.isEmpty() && doc.contains("peer")) {
                    val copy=doc.copy();copy["type"]="qwdtt"
                    val outbound=OutboundFactory.newByType("qwdtt")
                    if(outbound.parseFromJson(copy)) produce(outbound) else log("Invalid qwdtt profile")
                    return
                }
                // Re-import our own exported profiles as editable native objects, but keep arbitrary
                // sing-box extensions verbatim when a model would discard or reinterpret a field.
                if(type.isNotEmpty() && OutboundFactory.canonicalType(type) in OutboundFactory.ALL_TYPES) {
                    val outbound=OutboundFactory.newByType(type)
                    if(!outbound.invalid && outbound.parseFromJson(doc)) {
                        val exported=outbound.exportToJson()
                        if(doc.all { (key,value) -> exported.contains(key) &&
                                app.rawline.mobile.outbound.json.JsonWriter.write(value)==app.rawline.mobile.outbound.json.JsonWriter.write(exported[key]) }) {
                            produce(outbound);return
                        }
                    }
                }
            }
            val xrayType = xraySubType(doc)
            if (xrayType == XraySubType.OutboundObject) {
                xrayOutbound(doc as JsonObject, 1)
                return
            }
            if (xrayType != XraySubType.Invalid) {
                xray(doc, xrayType)
                return
            }

            val subType = singBoxSubType(doc)
            if (subType == SingBoxSubType.OutboundObject) {
                val tag = (doc as JsonObject).string("tag")
                val custom = Custom.fromSingBoxOutboundText(text)
                accept(custom, custom != null, ArrayList()) { singBoxSource(false, tag, 1) }
                return
            }
            if (subType != SingBoxSubType.Invalid) {
                singBox(doc, subType)
                return
            }

            if (text.contains("version") && text.contains("servers")) sip008(doc)
        }

        /** getSingBoxSubType (SubscriptionParser.cpp). */
        private fun singBoxSubType(doc: Any): SingBoxSubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") || doc.contains("endpoints")) return SingBoxSubType.OutboundInJson
                if (doc.contains("type")) return SingBoxSubType.OutboundObject
                return SingBoxSubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject && first.contains("type")) return SingBoxSubType.OutboundJsonArray
            }
            return SingBoxSubType.Invalid
        }

        /** getXraySubType (SubscriptionParser.cpp): Xray tags outbounds with `protocol` where sing-box uses `type`. */
        private fun xraySubType(doc: Any): XraySubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") && hasXrayOutbound(doc.array("outbounds"))) return XraySubType.OutboundInJson
                if (doc.contains("protocol") && !doc.contains("type") && !(doc.contains("server") && doc.contains("method"))) return XraySubType.OutboundObject
                return XraySubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject) {
                    if (first.contains("protocol") && !first.contains("type")) return XraySubType.OutboundJsonArray
                    if (first.contains("outbounds") && hasXrayOutbound(first.array("outbounds"))) return XraySubType.ConfigJsonArray
                }
            }
            return XraySubType.Invalid
        }

        private fun hasXrayOutbound(outbounds: JsonArray): Boolean =
            outbounds.any { it is JsonObject && it.contains("protocol") && !it.contains("type") }

        /** Parser::singBox (SubscriptionParser.cpp). */
        private fun singBox(doc: Any, type: SingBoxSubType) {
            val outbounds: JsonArray
            val endpoints: JsonArray
            when (type) {
                SingBoxSubType.OutboundInJson -> {
                    val json = doc as JsonObject
                    outbounds = json.array("outbounds")
                    endpoints = json.array("endpoints")
                }

                SingBoxSubType.OutboundJsonArray -> {
                    outbounds = doc as JsonArray
                    endpoints = JsonArray()
                }

                else -> return
            }
            fun handle(value: Any, endpoint: Boolean, index: Int) {
                val out = value as? JsonObject ?: return
                val source = { singBoxSource(endpoint, out.string("tag"), index) }
                if (out.isEmpty()) {
                    report(source, false, listOf(parserWarning(vtr(R.string.validation_import_empty_object))))
                    return
                }
                val type = out.string("type")
                val profileType = OutboundFactory.typeForSingBox(type)
                if (profileType == null) {
                    if (isSingBoxInfraType(type)) return
                    val before = produced.size + reports.size
                    json(out, out.toCompact(), 1)
                    if (produced.size + reports.size != before) return
                    val message = if (type.isEmpty()) vtr(R.string.validation_import_missing_type)
                    else vtr(R.string.validation_import_singbox_unsupported, type)
                    report(source, false, listOf(parserWarning(message)))
                    return
                }
                val outbound = OutboundFactory.newByType(profileType)
                val issues = ArrayList<Issue>()
                val parsed = !outbound.invalid && guarded(issues) { outbound.parseFromJson(out) }
                accept(outbound, parsed, issues, source)
            }
            for ((i, outbound) in outbounds.withIndex()) handle(outbound, false, i + 1)
            for ((i, endpoint) in endpoints.withIndex()) handle(endpoint, true, i + 1)
        }

        /** Parser::xray (SubscriptionParser.cpp). */
        private fun xray(doc: Any, type: XraySubType) {
            // Each element is a self-contained config (balancers, dialerProxy chains) that must run verbatim.
            if (type == XraySubType.ConfigJsonArray) {
                for ((i, c) in (doc as JsonArray).withIndex()) {
                    val cfg = c as? JsonObject ?: continue
                    val custom = Custom.fromXrayFullConfig(cfg) ?: continue
                    val remarks = cfg.string("remarks")
                    accept(custom, true, ArrayList()) { xrayConfigSource(remarks, i + 1) }
                }
                return
            }
            val outbounds: JsonArray = when (type) {
                XraySubType.OutboundInJson -> (doc as JsonObject).array("outbounds")
                XraySubType.OutboundJsonArray -> doc as JsonArray
                else -> return
            }
            if (type == XraySubType.OutboundInJson && keepsWhole(outbounds)) {
                val cfg = doc as JsonObject
                val custom = Custom.fromXrayFullConfig(cfg)
                accept(custom, custom != null, ArrayList()) { xrayConfigSource(cfg.string("remarks"), 1) }
                return
            }
            for ((i, o) in outbounds.withIndex()) {
                val out = o as? JsonObject ?: continue
                xrayOutbound(out, i + 1)
            }
        }

        /**
         * Parser::xrayOutbound (SubscriptionParser.cpp): infrastructure protocols are dropped; a VLESS outbound becomes
         * an xrayvless profile unless the typed model would drop a security key Xray reads and keeping it verbatim is
         * safe; everything else is a custom `xrayoutbound` whose config is the Indented JSON text, named after the tag.
         * Android keeps a VLESS outbound over a network the xrayvless profile does not model custom too.
         */
        private fun xrayOutbound(out: JsonObject, index: Int) {
            if (out.isEmpty() || isXrayInfraProtocol(out.string("protocol"))) return
            val source = { xraySource(out.string("tag"), index) }
            val verbatimIssues = ArrayList<Issue>()
            if (out.string("protocol") == "vless") {
                val issues = ArrayList<Issue>()
                xrayVlessNotes(out, issues)
                val normalized = XrayVless.normalizeForParse(out)
                if (normalized != null) {
                    val vless = XrayVless()
                    if (guarded(issues) { vless.parseFromJson(normalized) } && vless.getXrayStream().network in xrayNetworks) {
                        val unmodelled = xrayUnmodelledKeys(out)
                        val checked = ArrayList<Issue>()
                        if (unmodelled.isNotEmpty()) {
                            try {
                                vless.validate(checked, ctx)
                            } catch (e: RuntimeException) {
                                checked.add(parserError(e.message ?: e.javaClass.simpleName))
                            }
                        }
                        // A verbatim copy keeps the original values, so it fits only when Xray accepts them and the typed parse fixed nothing.
                        if (unmodelled.isEmpty() || Validation.hasError(checked) || issues.isNotEmpty() || xrayTypedRepaired(out, vless)) {
                            for (key in unmodelled) issues.add(xrayUnmodelledIssue(key))
                            accept(vless, true, issues, source)
                            return
                        }
                        verbatimIssues.add(parserWarning(vtr(R.string.validation_import_xray_verbatim, unmodelled.joinToString(", ") { it.path })))
                    }
                }
            }
            // Kept verbatim, so nothing was lost; the notes of a failed typed parse no longer apply.
            val custom = Custom()
            custom.subtype = Custom.CUSTOM_XRAY_OUTBOUND
            custom.config = JsonWriter.writeIndented(out)
            val tag = out.string("tag")
            if (tag.isNotEmpty()) custom.name = tag
            accept(custom, true, verbatimIssues, source)
        }

        /**
         * #88 (Android only): an outbound that dials through a sibling (`sockopt.dialerProxy`, `proxySettings.tag`)
         * only runs next to it, so the config stays one profile when it has exactly one egress: a proxy outbound no
         * sibling dials through. Several egresses sharing a dialer (a fragmenting freedom, say) are separate servers
         * and split as on the desktop.
         */
        private fun keepsWhole(outbounds: JsonArray): Boolean {
            val objects = outbounds.mapNotNull { it as? JsonObject }
            val tags = objects.mapNotNullTo(HashSet()) { o -> o.string("tag").takeIf { it.isNotEmpty() } }
            val targeted = HashSet<String>()
            for (out in objects) {
                val dialer = out.obj("streamSettings").obj("sockopt").string("dialerProxy")
                val proxy = out.obj("proxySettings").string("tag")
                for (target in arrayOf(dialer, proxy)) if (target.isNotEmpty() && target in tags) targeted.add(target)
            }
            if (targeted.isEmpty()) return false
            val egresses = objects.count { out ->
                val tag = out.string("tag")
                !isXrayInfraProtocol(out.string("protocol")) && (tag.isEmpty() || tag !in targeted)
            }
            return egresses == 1
        }

        /** Parser::sip008 (SubscriptionParser.cpp). */
        private fun sip008(doc: Any) {
            val servers = (doc as? JsonObject)?.array("servers") ?: return
            for ((i, o) in servers.withIndex()) {
                val index = i + 1
                val source = { vtr(R.string.validation_import_sip008_server, index.toString()) }
                val out = o as? JsonObject ?: JsonObject()
                if (out.isEmpty()) {
                    report(source, false, listOf(parserWarning(vtr(R.string.validation_import_empty_server))))
                    continue
                }
                val shadowsocks = Shadowsocks()
                val issues = ArrayList<Issue>()
                val parsed = guarded(issues) { shadowsocks.parseFromSip008(out) }
                accept(shadowsocks, parsed, issues, source)
            }
        }

        /** Parser::clash (SubscriptionParser.cpp): one entry at a time; an unreadable entry is reported, the rest still import. */
        private fun clash(text: String) {
            try {
                val doc = ClashYaml.document(text)
                val proxies = doc.proxies
                if (proxies.isNullOrEmpty()) {
                    val message = if (doc.mapping && doc.proxyProviders) vtr(R.string.validation_import_clash_providers)
                    else vtr(R.string.validation_import_clash_no_proxies)
                    reportDocument("", listOf(parserError(message)))
                    return
                }
                for ((i, node) in proxies.withIndex()) {
                    val index = i + 1
                    try {
                        clashProxy(node, index)
                    } catch (e: Exception) {
                        val reason = e.message?.takeIf { it.isNotBlank() }?.let { vtr(R.string.validation_import_clash_unreadable_reason, it) }
                            ?: vtr(R.string.validation_import_clash_unreadable)
                        report({ clashSource(ClashProxy(node).string("name"), index) }, false, listOf(parserError(reason)))
                    }
                }
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() }?.let { vtr(R.string.validation_import_clash_doc_reason, it) }
                    ?: vtr(R.string.validation_import_clash_doc)
                reportDocument("", listOf(parserError(reason)))
            }
        }

        /** Parser::clashProxy: the scope covers the whole read of the entry, as the desktop's struct decode does. */
        private fun clashProxy(node: JsonObject, index: Int) {
            var outbound: Outbound? = null
            var name = ""
            var unsupported = ""
            var infra = false
            var parsed = false
            val issues = ArrayList<Issue>()
            val scope = ParseDiag.Scope()
            try {
                val proxy = ClashProxy(node)
                name = proxy.string("name")
                val profileType = OutboundFactory.typeForClash(proxy.type)
                if (profileType == null) {
                    infra = proxy.type in CLASH_INFRA_TYPES
                    unsupported = proxy.string("type")
                } else {
                    val created = OutboundFactory.newByType(if (clashUsesXrayVless(proxy)) "xrayvless" else profileType)
                    parsed = created.parseFromClash(node)
                    // #63 (Android only): what the entry asked for that its parse dropped without a trace.
                    if (parsed) CoreSupport.clashProblem(proxy, created)?.let { issues.add(parserError(it)) }
                    outbound = created
                }
            } finally {
                issues.addAll(0, scope.take())
                scope.close()
            }
            val source = { clashSource(name, index) }
            if (outbound == null) {
                if (infra) return
                issues.add(
                    parserWarning(
                        if (unsupported.isEmpty()) vtr(R.string.validation_import_missing_type)
                        else vtr(R.string.validation_import_clash_unsupported, unsupported)
                    )
                )
                report(source, false, issues)
                return
            }
            accept(outbound, parsed, issues, source)
        }

        /** clashUsesXrayVless (SubscriptionParser.cpp): sing-box VLESS rejects the vision-udp443 flow; Xray accepts it. */
        private fun clashUsesXrayVless(proxy: ClashProxy): Boolean {
            if (proxy.type != "vless") return false
            val flow = proxy.string("flow").trim().lowercase()
            val singBoxFlow = flow.isEmpty() || flow == "none" || flow == "xtls-rprx-vision"
            val encryption = proxy.string("encryption")
            return proxy.string("network") == "xhttp" || (encryption.isNotEmpty() && encryption != "none") || !singBoxFlow ||
                clashRealityOnXray(proxy)
        }

        /**
         * clashRealityOnXray (SubscriptionParser.cpp): the links' REALITY preference ([OutboundFactory.useXrayVless]),
         * limited to the networks Xray runs REALITY over; sing-box also carries ws.
         */
        private fun clashRealityOnXray(proxy: ClashProxy): Boolean {
            if (!proxy.bool("tls") || proxy.obj("reality-opts").string("public-key").isEmpty()) return false
            if (preference == OutboundFactory.XrayVlessPreference.XhttpOnly) return false
            val network = proxy.string("network")
            return network.isEmpty() || network == "tcp" || network == "grpc"
        }

        /** Parser::wireguardFile (SubscriptionParser.cpp). */
        private fun wireguardFile(text: String, overrideName: String) {
            val wireguard = WireGuard()
            val issues = ArrayList<Issue>()
            val parsed = guarded(issues) { wireguard.parseFromLink(text) }
            if (parsed) {
                val names = extractWireGuardNames(text)
                setNameIfEmpty(wireguard, overrideName, names.explicitName, names.peerComment)
            } else if (!Validation.hasError(issues)) {
                if (wireguard.private_key.isBlank()) {
                    issues.add(parserError(vtr(R.string.validation_import_wg_no_private_key), "PrivateKey"))
                }
                if (wireguard.peer.public_key.isBlank()) {
                    issues.add(parserError(vtr(R.string.validation_import_wg_no_public_key), "PublicKey"))
                }
            }
            accept(wireguard, parsed, issues) {
                namedFileSource(vtr(R.string.validation_import_kind_wireguard), if (parsed) wireguard.name else overrideName)
            }
        }

        /** Parser::openVpnFile (SubscriptionParser.cpp). */
        private fun openVpnFile(text: String) {
            val problems = ArrayList<String>()
            val openVpn = OpenVpn()
            val issues = ArrayList<Issue>()
            val ok = guarded(issues) { openVpn.parseOvpnConfig(text, problems) }
            appendImporterNotes(issues, problems, ok)
            accept(openVpn, ok, issues) { namedFileSource(vtr(R.string.validation_import_kind_openvpn), openVpn.name) }
        }

        /** Parser::openConnectProfile (SubscriptionParser.cpp). */
        private fun openConnectProfile(text: String) {
            val problems = ArrayList<String>()
            val kind = vtr(R.string.validation_import_kind_openconnect)
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) {
                val hosts = ArrayList<OpenConnect>()
                val issues = ArrayList<Issue>()
                val ok = guarded(issues) { OpenConnect.parseAnyConnectXml(text, hosts, problems) }
                appendImporterNotes(issues, problems, ok)
                if (!ok) {
                    if (!Validation.hasError(issues)) issues.add(parserError(vtr(R.string.validation_import_unparsed)))
                    reportDocument(kind, issues)
                    return
                }
                if (issues.isNotEmpty()) reportDocument(kind, issues)
                for (host in hosts) accept(host, true, ArrayList()) { namedFileSource(kind, host.name) }
                return
            }
            val openConnect = OpenConnect()
            val issues = ArrayList<Issue>()
            val ok = guarded(issues) { openConnect.parseOpenConnectProfile(text, problems) }
            appendImporterNotes(issues, problems, ok)
            accept(openConnect, ok, issues) { namedFileSource(kind, openConnect.name) }
        }

        /**
         * Parser::link (SubscriptionParser.cpp): comments and short lines are skipped, json:// and throne://add/ carry
         * JSON, vless:// goes to xrayvless when [OutboundFactory.useXrayVless] says so, everything else follows the
         * scheme table. [overrideName] only reaches a link of a wireguard scheme.
         */
        private fun link(line: String, depth: Int, overrideName: String) {
            try {
                // Bot exports wrap qWDTT share links in Markdown code spans.
                var body = line
                if (body.length >= 2 && body.first() == '`' && body.last() == '`') {
                    val unwrapped = body.substring(1, body.length - 1).trim()
                    if (OutboundFactory.typeForScheme(unwrapped) in listOf("qwdtt", "wdttplus")) body = unwrapped
                }
                linkBody(body, depth, overrideName)
            } catch (e: Exception) {
                report({ linkSource(line, null) }, false, listOf(parserError(unreadable(e))))
            }
        }

        private fun linkBody(line: String, depth: Int, overrideName: String) {
            if (line.startsWith("//") || line.startsWith("#") || line.length < 2) return
            // json:// and rawline://add/ carry any JSON document: one profile, an array, or a whole Xray config.
            val addLink = app.rawline.mobile.route.UrlText.isAddLink(line)
            if (line.startsWith("json://", ignoreCase = true) || addLink) {
                val payload = OutboundFactory.decodeJsonLink(line, addLink)
                if (payload == null) report({ elide(line) }, false, listOf(parserError(vtr(R.string.validation_import_link_damaged_base64))))
                else document(payload, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = overrideName)
                return
            }
            if (line.startsWith("phobos://", ignoreCase = true)) {
                phobosLink(line)
                return
            }
            if (line.startsWith(app.rawline.mobile.outbound.types.Mrtt.SUBSCRIPTION_SCHEME, ignoreCase = true)) {
                val links = app.rawline.mobile.outbound.types.Mrtt.decodeSubscription(line)
                if (links == null) log("Invalid MRTT subscription")
                else for ((name, link) in links) {
                    val outbound = OutboundFactory.newByType("mrtt")
                    if (outbound.parseFromLink(link)) { outbound.name = name; produce(outbound) }
                }
                return
            }
            if (line.startsWith("vpn://", ignoreCase = true)) {
                vpnLink(line, depth)
                return
            }
            val profileType = OutboundFactory.typeForScheme(line)
            if (profileType == null) {
                if (isRoutingLink(line)) return
                val written = linkScheme(line)
                if (written.isNotEmpty()) {
                    val lower = written.lowercase()
                    val known = lower == "json" || OutboundFactory.typeForScheme("$lower://") != null
                    val message = if (known) vtr(R.string.validation_import_link_lowercase, lower, written)
                    else vtr(R.string.validation_import_link_unsupported, written)
                    report({ linkSource(line, null) }, false, listOf(parserWarning(message)))
                }
                return
            }
            // Our scheme table: one DNS tunnel model takes the type of the transport it names, VLESS picks its engine.
            val issues = ArrayList<Issue>()
            var outbound: Outbound? = null
            val parsed = ParseDiag.scoped(issues) { outbound = OutboundFactory.parseLink(line, preference); outbound != null }
            if (parsed && profileType == "wireguard") setNameIfEmpty(outbound!!, overrideName)
            accept(outbound, parsed, issues) { linkSource(line, outbound) }
        }

        /** A Phobos link is the client's whole .conf in base64url: WireGuard behind the obfuscator, or its SOCKS5 mode. */
        private fun phobosLink(line: String) {
            var raw = line.substring(9)
            var name = ""
            val fragment = raw.indexOf('#')
            if (fragment != -1) {
                name = LinkCodec.decodeFully(raw.substring(fragment + 1)).trim()
                raw = raw.substring(0, fragment)
            }
            if (name == "none") name = ""
            val conf = Base64Strict.decode(raw.trim().trimEnd('='), urlSafe = true)?.toString(Charsets.UTF_8).orEmpty()
            val outbound: Outbound = if (Regex("(?im)^\\s*mode\\s*=\\s*socks5\\s*$").containsMatchIn(conf)) {
                Socks().takeIf { it.parsePhobosConf(conf) }
            } else {
                WireGuard().takeIf { it.parseFromLink(conf) }
            } ?: run { log("Invalid Phobos link"); return }
            setNameIfEmpty(outbound, name)
            produce(outbound)
        }

        /**
         * Parser::vpnLink (SubscriptionParser.cpp), the AmneziaVPN share link: base64 (standard, then url-safe),
         * optionally Qt-compressed, holding either the `containers` JSON (each protocol's `last_config` carries a
         * config document) or a config document itself. The #fragment, else the export's `description` or `name`,
         * becomes the name of the WireGuard profiles inside.
         */
        private fun vpnLink(line: String, depth: Int) {
            var raw = line.substring(6)
            var fragmentName = ""
            val fragment = raw.indexOf('#')
            if (fragment != -1) {
                fragmentName = LinkCodec.decodeFully(raw.substring(fragment + 1)).trim()
                raw = raw.substring(0, fragment)
            }
            val source = { namedFileSource("vpn://", fragmentName) }
            raw = LinkCodec.decodeFully(raw)
            val decoded = Base64Strict.decode(raw)?.takeIf { it.isNotEmpty() }
                ?: Base64Strict.decode(raw, urlSafe = true)?.takeIf { it.isNotEmpty() }
            if (decoded == null) {
                report(source, false, listOf(parserError(vtr(R.string.validation_import_vpn_undecodable))))
                return
            }
            val data = uncompressVpnPayload(decoded) ?: decoded

            val before = produced.size
            val reportedBefore = reports.size
            val text = String(data, Charsets.UTF_8)
            val trimmed = Scan.trim(text)
            val doc = if (trimmed.startsWith("{") && Scan.matchingClose(trimmed, 0) == trimmed.length - 1) {
                JsonInput.parseObjectOrNull(trimmed)
            } else {
                null
            }
            if (doc != null && doc.contains("containers")) {
                var jsonName = doc.string("description").trim()
                if (jsonName.isEmpty()) jsonName = doc.string("name").trim()
                val targetName = fragmentName.ifEmpty { jsonName }

                val entries = ArrayList<VpnConfigEntry>()
                var wgCount = 0

                for (container in doc.array("containers")) {
                    val containerObj = container as? JsonObject ?: continue
                    val containerType = containerObj.string("container").trim()

                    for (key in containerObj.keys()) {
                        val protoObj = containerObj[key] as? JsonObject ?: continue
                        val lastObj = when (val lastConfig = protoObj["last_config"]) {
                            is String -> JsonInput.parseObjectOrNull(lastConfig)
                            is JsonObject -> lastConfig
                            else -> null
                        }
                        var conf = when (val lastConfig = protoObj["last_config"]) {
                            is String -> if (lastObj != null && lastObj.contains("config")) lastObj.string("config") else lastConfig
                            is JsonObject -> lastConfig.string("config")
                            else -> ""
                        }
                        if (conf.isEmpty()) continue

                        val isWireGuard = hasWireGuardSections(conf)
                        // AmneziaVPN fills "DNS = $PRIMARY_DNS, $SECONDARY_DNS" from the export's dns1/dns2 (its defaults when absent).
                        conf = conf.replace("\$PRIMARY_DNS", doc.string("dns1").trim().ifEmpty { "1.1.1.1" })
                            .replace("\$SECONDARY_DNS", doc.string("dns2").trim().ifEmpty { "1.0.0.1" })
                        // The MTU sits beside the config (AmneziaVPN default 1376: AWG padding does not fit WireGuard's 1420).
                        if (isWireGuard && !Regex("""(?im)^\s*MTU\s*=""").containsMatchIn(conf)) {
                            val mtu = (lastObj?.get("mtu")?.let { if (it is String) it.trim() else app.rawline.mobile.outbound.json.JsonValues.toInt(it).toString() })
                                ?.takeIf { (it.toIntOrNull() ?: 0) in 576..65535 } ?: "1376"
                            conf = conf.replaceFirst(Regex("""(?im)^\s*\[Interface\]\s*$"""), "[Interface]\nMTU = $mtu")
                        }
                        if (isWireGuard) wgCount++
                        entries.add(VpnConfigEntry(conf, containerType, isWireGuard))
                    }
                }

                for (e in entries) {
                    // Several WireGuard confs in one export would share a single name, so each is tagged with its container type.
                    var name = targetName
                    if (e.isWireGuard && wgCount > 1 && name.isNotEmpty() && e.containerType.isNotEmpty()) {
                        name += " (" + e.containerType + ")"
                    }
                    document(e.conf, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = name)
                }
            } else {
                document(text, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = fragmentName)
            }
            // Inner items that failed already said why.
            if (produced.size == before && reports.size == reportedBefore) {
                report(source, false, listOf(parserError(vtr(R.string.validation_import_vpn_empty))))
            }
        }

        /**
         * uncompressVpnPayload (SubscriptionParser.cpp): qUncompress data (4-byte big-endian size, then a zlib
         * stream) after validating the header; null when it is not such data.
         */
        private fun uncompressVpnPayload(data: ByteArray): ByteArray? {
            if (data.size < 6) return null
            val expected = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or
                ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
            val cmf = data[4].toInt() and 0xFF
            val flg = data[5].toInt() and 0xFF
            if (expected == 0L || expected > MAX_VPN_PAYLOAD || (cmf and 0x0F) != 8 || ((cmf shl 8) or flg) % 31 != 0) {
                return null
            }
            val inflater = Inflater()
            return try {
                inflater.setInput(data, 4, data.size - 4)
                val out = ByteArrayOutputStream(minOf(expected, 1L shl 20).toInt())
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && !inflater.finished()) return null
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_VPN_PAYLOAD) return null
                }
                out.toByteArray()
            } catch (e: Exception) {
                null
            } finally {
                inflater.end()
            }
        }

        /** looksLikeOvpnConfig (SubscriptionParser.cpp:181-198). */
        private fun looksLikeOvpnConfig(text: String): Boolean {
            var hasRemote = false
            var hasClientMarker = false
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#' || line[0] == ';') return@forEachLine true
                if (line.startsWith("remote ") || line == "<ca>" || line == "<tls-auth>" || line == "<tls-crypt>" ||
                    line == "<tls-crypt-v2>" || line == "<secret>"
                ) {
                    hasRemote = true
                }
                if (line == "client" || line == "tls-client" || line.startsWith("dev ") || line.startsWith("dev-type ") ||
                    line.startsWith("proto ")
                ) {
                    hasClientMarker = true
                }
                result = hasRemote && hasClientMarker
                !result
            }
            return result
        }

        /** looksLikeOpenConnectProfile (SubscriptionParser.cpp:200-224). */
        private fun looksLikeOpenConnectProfile(text: String): Boolean {
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) return true
            var matched = false
            if (text.contains("protocol")) {
                Scan.forEachLine(text) { raw ->
                    if (!raw.contains("protocol")) return@forEachLine true
                    matched = OPENCONNECT_CLI.containsMatchIn(raw) || OPENCONNECT_FILE.matches(raw)
                    !matched
                }
            }
            if (matched) return true
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#') return@forEachLine true
                result = line.startsWith("openconnect ")
                false
            }
            return result
        }
    }

    private val CSQTT_HTML_LINK = Regex("""<(?:a|input)\b[^<>]*?\b(?:href|value)\s*=\s*["'](csqtt://[^"'<>\s]+)["'][^<>]*>""", RegexOption.IGNORE_CASE)
    private val OPENCONNECT_CLI = Regex("""(?:^|\s)--protocol[= ](?:anyconnect|nc|gp|pulse|f5|fortinet)\b""")
    private val OPENCONNECT_FILE = Regex("""[ \t]*protocol[ \t]*=[ \t]*(?:anyconnect|nc|gp|pulse|f5|fortinet)[ \t]*""")

    /** hasWireGuardSections (SubscriptionParser.cpp:103-106): shared by document and vpnLink so both agree on what a WireGuard conf is. */
    private fun hasWireGuardSections(text: String): Boolean = text.contains("[Interface]") && text.contains("[Peer]")

    // PCRE2's \s and \w spelled out: Unicode-wide where the desktop sets UseUnicodePropertiesOption, ASCII elsewhere.
    // java.util.regex (ASCII by default) and Android's ICU engine (Unicode) would each get one of them wrong.
    private val WG_NAME_SEPARATOR = Regex("""[\p{Z}\t\n\u000B\f\r\u0085_\-.:]+""")
    private val WG_COMMENT_MARKERS = Regex("""^[#;\p{Z}\t\n\u000B\f\r\u0085]+""")
    private val WG_DIRECTIVE = Regex("""^[A-Za-z0-9_]+[ \t\n\u000B\f\r]*=""")
    private val WG_EXPLICIT_NAME = Regex("""^(?:name|remarks?)[ \t\n\u000B\f\r]*[:=][ \t\n\u000B\f\r]*(.+)$""", RegexOption.IGNORE_CASE)
    private val WG_GENERIC_WORDS = setOf("peer", "server", "wireguard", "configuration", "config", "interface", "client", "wg")

    /**
     * looksLikeName (SubscriptionParser.cpp:108-121): filters dividers and boilerplate headers ("# WireGuard
     * configuration", "# Peer 1") so only a real node name can become a profile name.
     */
    private fun looksLikeName(comment: String): Boolean {
        // QChar::isLetterOrNumber covers every L* and N* category; Char.isLetterOrDigit stops at Nd
        if (comment.none { it.category.code[0] in "LN" }) return false
        for (word in comment.lowercase().split(WG_NAME_SEPARATOR)) {
            val w = word.trimEnd { it.isDigit() }
            if (w.isEmpty()) continue
            if (w !in WG_GENERIC_WORDS) return true
        }
        return false
    }

    /** WireGuardNames (SubscriptionParser.cpp:123-126). */
    private class WireGuardNames {
        var explicitName = ""
        var peerComment = ""
    }

    /**
     * extractWireGuardNames (SubscriptionParser.cpp:128-163): an explicit `name`/`remark(s)` comment, and the comment
     * directly above the last `[Peer]`. Any blank or config line in between drops that comment, so `[Interface]`
     * comments cannot leak in.
     */
    private fun extractWireGuardNames(text: String): WireGuardNames {
        val out = WireGuardNames()
        var lastComment = ""

        Scan.forEachLine(text) { raw ->
            val line = Scan.trim(raw)
            val isComment = line.isNotEmpty() && (line[0] == '#' || line[0] == ';')
            if (!isComment) {
                // Assigned on every [Peer]: the name comes from the last peer, the one WireGuard.parseFromLink keeps.
                if (line.startsWith("[peer]", ignoreCase = true)) out.peerComment = lastComment
                lastComment = ""
                return@forEachLine true
            }

            val comment = WG_COMMENT_MARKERS.replace(line, "").trim()

            if (out.explicitName.isEmpty()) {
                WG_EXPLICIT_NAME.find(comment)?.let { out.explicitName = it.groupValues[1].trim() }
            }

            // Commented-out options such as "# DNS = 1.1.1.1" are not names.
            lastComment = if (!WG_DIRECTIVE.containsMatchIn(comment) && looksLikeName(comment)) comment else ""
            true
        }

        return out
    }

    /**
     * setNameIfEmpty (SubscriptionParser.cpp:165-175): candidates in priority order; a name the link parse already
     * read from a #fragment is never overwritten.
     */
    private fun setNameIfEmpty(outbound: Outbound, vararg candidates: String) {
        if (outbound.name.trim().isNotEmpty()) return
        for (c in candidates) {
            val t = c.trim()
            if (t.isNotEmpty()) {
                outbound.name = t
                return
            }
        }
    }

    /** ConfigEntry of Parser::vpnLink (SubscriptionParser.cpp:669-673): one config document of a `containers` export. */
    private class VpnConfigEntry(val conf: String, val containerType: String, val isWireGuard: Boolean)

    /** Subscription::scan (include/configs/sub/SubscriptionScan.hpp) on strings. */
    internal object Scan {
        private fun isSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

        private fun isBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

        private fun isUrlSafeBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '='

        /** scan::trim: ASCII whitespace and NBSP on both ends, a BOM at the front. */
        fun trim(s: String): String {
            var start = 0
            var end = s.length
            while (start < end) {
                val c = s[start]
                if (isSpace(c) || c == '\u00A0' || c == '\uFEFF') start++ else break
            }
            while (end > start) {
                val c = s[end - 1]
                if (isSpace(c) || c == '\u00A0') end-- else break
            }
            return s.substring(start, end)
        }

        /** scan::matchingClose: the index of the bracket closing the one at [open], honouring JSON string escapes; -1 when unbalanced. */
        fun matchingClose(s: String, open: Int): Int {
            var depth = 0
            var inString = false
            var escaped = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else if (c == '"') {
                    inString = true
                } else if (c == '{' || c == '[') {
                    depth++
                } else if (c == '}' || c == ']') {
                    if (--depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** scan::forEachLine: the segments of QString::split('\n'); [fn] returns false to stop. */
        fun forEachLine(s: String, fn: (String) -> Boolean) {
            var idx = 0
            while (true) {
                val nl = s.indexOf('\n', idx)
                if (nl < 0) {
                    fn(s.substring(idx))
                    return
                }
                if (!fn(s.substring(idx, nl))) return
                idx = nl + 1
            }
        }

        /** scan::forEachItem: one item per line, except that a line opening a bracket yields the whole balanced block. */
        fun forEachItem(s: String, fn: (String) -> Unit) {
            val n = s.length
            var idx = 0
            while (idx < n) {
                if (s[idx] == '\n') {
                    idx++
                    continue
                }
                if (s[idx] == '{' || s[idx] == '[') {
                    val end = matchingClose(s, idx)
                    if (end >= 0) {
                        fn(s.substring(idx, end + 1))
                        idx = end + 1
                        continue
                    }
                }
                var nl = s.indexOf('\n', idx)
                if (nl < 0) nl = n
                fn(s.substring(idx, nl))
                idx = nl + 1
            }
        }

        fun looksLikeBase64(s: String): Boolean = s.isNotEmpty() && s.all { isBase64Char(it) }

        /** The url-safe alphabet, which only counts when a `-` or `_` proves it is not standard base64. */
        fun looksLikeUrlSafeBase64(s: String): Boolean =
            s.isNotEmpty() && s.all { isUrlSafeBase64Char(it) } && s.any { it == '-' || it == '_' }

        /** scan::looksLikeWrappedBase64: one blob wrapped at a fixed column, as `base64` and MIME emit it. */
        fun looksLikeWrappedBase64(s: String): Boolean {
            var width = 0
            var lines = 0
            var sawShort = false
            var ok = true
            forEachLine(s) { raw ->
                var line = raw
                while (line.isNotEmpty() && isSpace(line[line.length - 1])) line = line.substring(0, line.length - 1)
                if (line.isEmpty()) return@forEachLine true
                if (!line.all { isBase64Char(it) }) {
                    ok = false
                    return@forEachLine false
                }
                lines++
                if (width == 0) {
                    width = line.length
                    return@forEachLine true
                }
                if (line.length > width || sawShort) {
                    ok = false
                    return@forEachLine false
                }
                if (line.length < width) sawShort = true
                true
            }
            return ok && lines >= 2 && width % 4 == 0
        }

        /** scan::decodeBase64 (AbortOnBase64DecodingErrors): null when invalid or empty. */
        fun decodeBase64(s: String): String? = Base64Strict.decode(s)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        fun decodeBase64UrlSafe(s: String): String? =
            Base64Strict.decode(s, urlSafe = true)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        /** QByteArray::fromBase64 without abort: characters outside the alphabet (line breaks, padding) are skipped. */
        fun decodeBase64Lenient(s: String): String? {
            val filtered = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
            return decodeBase64(filtered)
        }
    }
}
