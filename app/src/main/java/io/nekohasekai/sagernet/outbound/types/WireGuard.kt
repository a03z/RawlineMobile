package app.rawline.mobile.outbound.types

import app.rawline.mobile.R
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.SecurityLevel
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.link.Hosts
import app.rawline.mobile.outbound.link.LinkCodec
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.outbound.link.ParsedLink
import app.rawline.mobile.validation.Empty
import app.rawline.mobile.validation.Issue
import app.rawline.mobile.validation.ParseDiag
import app.rawline.mobile.validation.Rule
import app.rawline.mobile.validation.Severity
import app.rawline.mobile.validation.Validation
import app.rawline.mobile.validation.acceptOptional
import app.rawline.mobile.validation.addIssue
import app.rawline.mobile.validation.jsonStringList
import app.rawline.mobile.validation.vtr

private const val WIREGUARD_INT_MAX = 2147483647L
private const val WIREGUARD_UINT32_MAX = 4294967295L

/** Without a diagnostics scope (DB loads, clones) a parse keeps every value as it always did. */
private fun wireguardKeep(field: String, value: String, rule: Rule): Boolean =
    !ParseDiag.active() || acceptOptional(field, rule(value), value)

/** S1-S4 must match the server's, so a bad one refuses the profile; Jc, Jmin and Jmax only shape what this side sends. */
private fun wireguardAmneziaInt(field: String, value: String, max: Long, mustMatchServer: Boolean): Int {
    val message = Validation.int(value, 0, max)
    if (message.isEmpty()) return QtStrings.toInt(value.trim())
    if (mustMatchServer) ParseDiag.error(field, message, value) else acceptOptional(field, message, value)
    return 0
}

/** Call after fixAddress(): what is left without a valid prefix the core cannot decode. */
private fun wireguardDropBadAddresses(addresses: MutableList<String>): MutableList<String> {
    if (!ParseDiag.active()) return addresses
    return addresses.filterTo(ArrayList()) { acceptOptional("address", Validation.cidr(it), it) }
}

/** wireguardFullTunnel (wireguard.cpp): full tunnels also come as halves (0.0.0.0/1 + 128.0.0.0/1) or as "everything but the LAN" lists. */
private fun wireguardFullTunnel(allowedIps: List<String>): Boolean {
    var v4 = 0.0
    var v6 = 0.0
    for (cidr in allowedIps) {
        val (network, prefix) = VpnFileImport.parseSubnet(cidr) ?: continue
        if (network.contains(':')) v6 += Math.scalb(1.0, -prefix) else v4 += Math.scalb(1.0, -prefix)
    }
    return v4 >= 0.5 || v6 >= 0.5
}

/** The Checks of wireguard.h, shared by the WireGuard editors (field text) and [WireGuard.validate] (profile fields). */
object WireGuardChecks {

    /** Three bytes separated by ',' or '-', as the editor and links write them; [bytes] receives them, or is cleared. */
    @JvmStatic
    @JvmOverloads
    fun wireguardReserved(text: String, bytes: MutableList<Int>? = null): String {
        bytes?.clear()
        val t = text.trim()
        if (t.isEmpty()) return ""
        val values = ArrayList<Int>()
        for (part in QtStrings.splitSkipEmpty(t.replace('-', ','), ",")) {
            val value = Validation.toInt(part)
            if (value == null || value < 0 || value > 255) {
                values.clear()
                break
            }
            values.add(value.toInt())
        }
        if (values.size != 3) return vtr(R.string.validation_wg_reserved)
        bytes?.addAll(values)
        return ""
    }

    /** Seconds, or an AmneziaWG range such as 22-30. */
    @JvmStatic
    fun wireguardKeepalive(text: String): String = Validation.intOrRange(text, 0, WIREGUARD_INT_MAX)

    /** wireguard-go UintRange: n or lo-hi, both uint32. */
    @JvmStatic
    fun amneziaRange(text: String): String = Validation.intOrRange(text, 0, WIREGUARD_UINT32_MAX)

    /** H1-H4 in order; unset ones are 1, 2, 3 and 4 in wireguard-go. Silent while one of them is not a range. */
    @JvmStatic
    fun amneziaHeaders(headers: List<String>): String {
        val ranges = ArrayList<Pair<Long, Long>>()
        for (i in 0 until 4) {
            val text = headers.getOrElse(i) { "" }.trim()
            if (text.isEmpty()) {
                ranges.add(Pair(i + 1L, i + 1L))
                continue
            }
            if (amneziaRange(text).isNotEmpty()) return ""
            val dash = text.indexOf('-')
            val lo = Validation.toInt(if (dash < 0) text else text.substring(0, dash)) ?: return ""
            val hi = if (dash < 0) lo else Validation.toInt(text.substring(dash + 1)) ?: return ""
            ranges.add(Pair(lo, hi))
        }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                if (ranges[i].first <= ranges[j].second && ranges[j].first <= ranges[i].second) {
                    return vtr(R.string.validation_wg_headers_overlap, (i + 1).toString(), (j + 1).toString())
                }
            }
        }
        return ""
    }

    @JvmStatic
    fun amneziaHeaderProtection(key: String, paddings: List<Long>): String {
        if (key.isBlank()) return ""
        for ((i, padding) in paddings.withIndex()) {
            if (padding < 12) return vtr(R.string.validation_wg_header_protection_padding, (i + 1).toString(), padding.toString())
        }
        return ""
    }

    @JvmStatic
    fun amneziaJunkSizes(jmin: Long, jmax: Long): String = if (jmin <= jmax) "" else vtr(R.string.validation_wg_junk_sizes)

    /** wireguard-go newObfChain: <b 0xHEX>, <r N>, <rc N>, <rd N>, <dz N>, <t>, <d>, <ds>; text outside tags is ignored. */
    @JvmStatic
    fun amneziaSignature(spec: String): String {
        var start = spec.indexOf('<')
        while (start >= 0) {
            val end = spec.indexOf('>', start)
            if (end < 0) return vtr(R.string.validation_wg_signature_unclosed)
            val parts = words(spec.substring(start + 1, end))
            start = spec.indexOf('<', end + 1)
            if (parts.isEmpty()) return vtr(R.string.validation_wg_signature_empty)
            val tag = parts[0]
            val argument = parts.getOrElse(1) { "" }
            when (tag) {
                "b" -> {
                    val hex = if (argument.startsWith("0x")) argument.substring(2) else argument
                    if (hex.isEmpty() || Validation.hex(hex, 0, 0).isNotEmpty()) return vtr(R.string.validation_wg_signature_hex)
                }

                "r", "rc", "rd", "dz" -> {
                    val length = Validation.toInt(if (argument.startsWith('+')) argument.substring(1) else argument)
                    if (length == null || length < 0) return vtr(R.string.validation_wg_signature_length, tag)
                }

                "t", "d", "ds" -> {}
                else -> return vtr(R.string.validation_wg_signature_unknown, tag)
            }
        }
        return ""
    }

    /** QString::simplified().split(' ', Qt::SkipEmptyParts). */
    private fun words(text: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in text) {
            if (!c.isWhitespace()) {
                word.append(c)
                continue
            }
            if (word.isNotEmpty()) words.add(word.toString())
            word.setLength(0)
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }
}

/** Peer (wireguard.h, wireguard.cpp): the single peer of a wireguard endpoint. */
class WireGuardPeer {
    @JvmField var address: String = ""
    @JvmField var port: Int = 0
    @JvmField var public_key: String = ""
    @JvmField var pre_shared_key: String = ""
    @JvmField var reserved: MutableList<Int> = ArrayList()

    /** Seconds, or an AmneziaWG 3.0 range such as "22-30". */
    @JvmField var persistent_keepalive: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** Peer::ParseFromLink; values are FullyDecoded because PrettyDecoded keeps %2F/%2B/%2C and corrupts base64 keys. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        address = url.host
        port = url.port(51820)
        when {
            q.has("public_key") -> public_key = q.valueFully("public_key")
            q.has("publickey") -> public_key = q.valueFully("publickey")
            q.has("peer_public_key") -> public_key = q.valueFully("peer_public_key")
        }
        when {
            q.has("pre_shared_key") -> pre_shared_key = q.valueFully("pre_shared_key")
            q.has("preshared_key") -> pre_shared_key = q.valueFully("preshared_key")
            q.has("presharedkey") -> pre_shared_key = q.valueFully("presharedkey")
            q.has("psk") -> pre_shared_key = q.valueFully("psk")
        }
        if (q.has("reserved")) {
            val raw = q.valueFully("reserved")
            // The core needs all three bytes, so a partial list is dropped rather than kept.
            val message = WireGuardChecks.wireguardReserved(raw, reserved)
            if (message.isNotEmpty()) acceptOptional("reserved", message, raw)
        }
        for (key in KEEPALIVE_KEYS) {
            if (!q.has(key)) continue
            val value = q.valueFully(key).trim()
            if (wireguardKeep(key, value) { WireGuardChecks.wireguardKeepalive(it) }) persistent_keepalive = value
            break
        }
        return true
    }

    /** Peer::ParseFromJson: the keepalive accepts a number or a range string, reserved a base64 string or a byte list. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("address")) address = obj.string("address")
        if (obj.contains("port")) port = obj.int("port")
        if (obj.contains("public_key")) public_key = obj.string("public_key")
        if (obj.contains("pre_shared_key")) pre_shared_key = obj.string("pre_shared_key")
        if (obj.contains("reserved")) {
            val value = obj["reserved"]
            var text = ""
            if (value is String) {
                // Go decodes a JSON string into []uint8 as base64.
                text = value
                if (Validation.base64Key(text, 3).isEmpty()) {
                    reserved = Base64Strict.decodeLenient(text).mapTo(ArrayList()) { it.toInt() and 0xFF }
                }
            } else {
                reserved = obj.array("reserved").mapTo(ArrayList()) { JsonValues.toInt(it) }
            }
            if (ParseDiag.active() && (reserved.isNotEmpty() || text.isNotEmpty())) {
                val bytes = if (reserved.isNotEmpty()) reserved.joinToString(",") else text
                val message = WireGuardChecks.wireguardReserved(bytes, reserved)
                if (message.isNotEmpty()) acceptOptional("reserved", message, bytes)
            }
        }
        if (obj.contains("persistent_keepalive_interval")) {
            val text = if (obj.isString("persistent_keepalive_interval")) obj.string("persistent_keepalive_interval")
            else obj.int("persistent_keepalive_interval").toString()
            if (wireguardKeep("persistent_keepalive_interval", text) { WireGuardChecks.wireguardKeepalive(it) }) {
                persistent_keepalive = text
            }
        }
        return true
    }

    /** Peer::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (public_key.isNotEmpty()) q.add("public_key" to public_key)
        if (pre_shared_key.isNotEmpty()) q.add("pre_shared_key" to pre_shared_key)
        if (reserved.isNotEmpty()) q.add("reserved" to reserved.joinToString("-"))
        if (persistent_keepalive.isNotEmpty()) q.add("persistent_keepalive_interval" to persistent_keepalive)
        return q
    }

    /** Peer::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (address.isNotEmpty()) obj["address"] = address
        if (port > 0) obj["port"] = port
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (pre_shared_key.isNotEmpty()) obj["pre_shared_key"] = pre_shared_key
        if (reserved.isNotEmpty()) obj["reserved"] = reservedArray()
        writeKeepalive(obj)
        return obj
    }

    /** Peer::Build: ExportToJson plus the catch-all allowed_ips. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj["allowed_ips"] = JsonValues.stringArray(listOf("0.0.0.0/0", "::/0"))
        return obj
    }

    private fun reservedArray(): JsonArray {
        val arr = JsonArray()
        for (value in reserved) arr.add(value)
        return arr
    }

    // Peer::WriteKeepalive: a range must be a string and a plain interval a number; anything else makes the core
    // refuse to start, so a value that is neither is dropped.
    private fun writeKeepalive(obj: JsonObject) {
        if (persistent_keepalive.isEmpty()) return
        val seconds = QtStrings.toIntOrNull(persistent_keepalive)
        if (seconds != null) {
            if (seconds > 0) obj["persistent_keepalive_interval"] = seconds
            return
        }
        if (KEEPALIVE_RANGE.containsMatchIn(persistent_keepalive)) obj["persistent_keepalive_interval"] = persistent_keepalive
    }

    companion object {
        private val KEEPALIVE_RANGE = Regex("^\\d+-\\d+$")
        private val KEEPALIVE_KEYS = listOf("persistent_keepalive_interval", "persistent_keepalive", "keepalive")
    }
}

/**
 * wireguard / AmneziaWG (include/configs/outbounds/wireguard.h, src/configs/outbounds/wireguard.cpp): a sing-box
 * endpoint. The server address and port live in [peer]; the base `server`/`serverPort` are only what the last link
 * parse left there, exactly as on the desktop, so callers use [getAddress]/[getPort].
 */
class WireGuard : Outbound("wireguard") {
    @JvmField var private_key: String = ""
    @JvmField var peer: WireGuardPeer = WireGuardPeer()
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1420
    @JvmField var system: Boolean = false
    @JvmField var worker_count: Int = 0
    @JvmField var udp_timeout: String = ""
    /** The INI `DNS =` resolvers; the official clients resolve through them, and some servers only forward what they resolved. */
    @JvmField var dns: MutableList<String> = ArrayList()

    /** AmneziaWG: jc/jmin/jmax and s1-s4 are integers; h1-h4 (magic headers) and i1-i5 (signature packets) are strings. */
    @JvmField var enable_amnezia: Boolean = false
    @JvmField var jc: Int = 0
    @JvmField var jmin: Int = 0
    @JvmField var jmax: Int = 0
    @JvmField var s1: Int = 0
    @JvmField var s2: Int = 0
    @JvmField var s3: Int = 0
    @JvmField var s4: Int = 0
    @JvmField var h1: String = ""
    @JvmField var h2: String = ""
    @JvmField var h3: String = ""
    @JvmField var h4: String = ""
    @JvmField var i1: String = ""
    @JvmField var i2: String = ""
    @JvmField var i3: String = ""
    @JvmField var i4: String = ""
    @JvmField var i5: String = ""

    /** AmneziaWG 3.0: header_protection_key is a base64 32-byte key; the rest are ranges ("30" or "22-30"). */
    @JvmField var header_protection_key: String = ""
    @JvmField var content_padding_addition: String = ""
    @JvmField var rekey_after_time: String = ""
    @JvmField var rekey_timeout: String = ""
    @JvmField var reject_after_time: String = ""
    @JvmField var keepalive_timeout: String = ""
    @JvmField var max_handshake_attempts: String = ""

    /** AmneziaWG 3.1 */
    @JvmField var random_trailers: Boolean = false
    @JvmField var disable_cookies: Boolean = false

    /** Obfuscator in front of the server: "wg-obfuscator" (ClusterM, Phobos) or "wgobfs" (xt_wgobfs); empty is plain WireGuard. */
    @JvmField var obfs_type: String = ""
    @JvmField var obfs_key: String = ""
    /** wg-obfuscator: NONE, STUN or MEDIA (an RTP video stream). */
    @JvmField var obfs_masking: String = ""
    /** -1 keeps the obfuscator's default of 4. */
    @JvmField var obfs_max_dummy: Int = -1
    @JvmField var obfs_obfuscate_bytes: Int = 0
    @JvmField var obfs_media_pt: Int = 0
    /** Decimal, empty when the SSRC is random. */
    @JvmField var obfs_media_ssrc: String = ""
    @JvmField var obfs_media_clock: Int = 0

    /**
     * wireguard::ParseFromLink: wg-quick / AmneziaWG INI text, or a wg:// / wireguard:// / awg:// URL. The scheme is
     * not checked, and awg:// alone does not enable AmneziaWG; only its parameters do, as on the desktop.
     */
    override fun parseFromLink(link: String): Boolean {
        if (link.contains("[Interface]") && link.contains("[Peer]")) return parseIni(link)

        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)

        when {
            q.has("private_key") -> private_key = q.valueFully("private_key")
            q.has("privatekey") -> private_key = q.valueFully("privatekey")
            url.userName.isNotEmpty() -> private_key = url.userName
        }

        peer.parseFromLink(url)
        server = peer.address
        serverPort = peer.port

        when {
            q.has("local_address") -> address = QtStrings.splitSkipEmpty(q.valueFully("local_address").replace(" ", "").replace('-', ','), ",")
            q.has("address") -> address = QtStrings.splitSkipEmpty(q.valueFully("address").replace(" ", ""), ",")
            q.has("ip") -> address = QtStrings.splitSkipEmpty(q.valueFully("ip").replace(" ", ""), ",")
        }

        if (q.has("mtu")) {
            val value = q.valueFully("mtu").trim()
            if (wireguardKeep("mtu", value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) mtu = QtStrings.toInt(value)
        }
        if (q.has("workers")) {
            val value = q.valueFully("workers").trim()
            if (wireguardKeep("workers", value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) worker_count = QtStrings.toInt(value)
        }
        if (q.has("use_system_interface")) system = q.valueFully("use_system_interface") == "true"
        if (q.has("udp_timeout")) {
            val value = q.valueFully("udp_timeout").trim()
            if (wireguardKeep("udp_timeout", value, Validation.Rules.duration())) udp_timeout = value
        }
        if (q.has("dns")) dns = QtStrings.splitSkipEmpty(q.valueFully("dns").replace(" ", ""), ",")

        if (q.valueFully("enable_amnezia") == "true") enable_amnezia = true
        fun intKey(key: String, max: Long, mustMatchServer: Boolean, set: (Int) -> Unit) {
            if (!q.has(key)) return
            set(wireguardAmneziaInt(key, q.valueFully(key), max, mustMatchServer))
            enable_amnezia = true
        }
        fun strKey(key: String, set: (String) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(q.valueFully(key))
            enable_amnezia = true
            return true
        }
        intKey("jc", WIREGUARD_INT_MAX, false) { jc = it }
        intKey("jmin", WIREGUARD_INT_MAX, false) { jmin = it }
        intKey("jmax", WIREGUARD_INT_MAX, false) { jmax = it }
        intKey("s1", 65535, true) { s1 = it }
        intKey("s2", 65535, true) { s2 = it }
        intKey("s3", 65535, true) { s3 = it }
        intKey("s4", 65535, true) { s4 = it }
        strKey("h1") { h1 = it.trim() }
        strKey("h2") { h2 = it.trim() }
        strKey("h3") { h3 = it.trim() }
        strKey("h4") { h4 = it.trim() }
        strKey("i1") { i1 = it }
        strKey("i2") { i2 = it }
        strKey("i3") { i3 = it }
        strKey("i4") { i4 = it }
        strKey("i5") { i5 = it }
        if (!strKey("header_protection_key") { header_protection_key = it }) strKey("headerprotectionkey") { header_protection_key = it }
        if (!strKey("content_padding_addition") { content_padding_addition = it }) strKey("contentpaddingaddition") { content_padding_addition = it }
        strKey("rekey_after_time") { rekey_after_time = it }
        strKey("rekey_timeout") { rekey_timeout = it }
        strKey("reject_after_time") { reject_after_time = it }
        strKey("keepalive_timeout") { keepalive_timeout = it }
        strKey("max_handshake_attempts") { max_handshake_attempts = it }
        strKey("random_trailers") { random_trailers = parseAmneziaBool(it) }
        strKey("disable_cookies") { disable_cookies = parseAmneziaBool(it) }

        if (q.has("obfs")) {
            obfs_type = q.valueFully("obfs")
            obfs_key = q.valueFully("obfs_key")
            obfs_masking = q.valueFully("obfs_masking").uppercase()
            if (q.has("obfs_max_dummy")) obfs_max_dummy = QtStrings.toInt(q.valueFully("obfs_max_dummy"))
            obfs_obfuscate_bytes = QtStrings.toInt(q.valueFully("obfs_bytes"))
            obfs_media_pt = QtStrings.toInt(q.valueFully("obfs_media_pt"))
            obfs_media_ssrc = ssrcText(q.valueFully("obfs_media_ssrc"))
            obfs_media_clock = QtStrings.toInt(q.valueFully("obfs_media_clock"))
        }

        fixAddress()
        address = wireguardDropBadAddresses(address)
        return !(private_key.isEmpty() || peer.public_key.isEmpty() || server.isEmpty())
    }

    // The INI half of wireguard::ParseFromLink: keys are matched lower-cased and reported as written; no base parse,
    // so the name and dial fields stay untouched.
    private fun parseIni(text: String): Boolean {
        var section = ""
        var target = ""
        var peers = 0
        val allowedIps = ArrayList<String>()
        for (line in QtStrings.split(text, "\n")) {
            // wg and wg-quick cut every line at '#'; keys and signature tags never contain one.
            val comment = line.indexOf('#')
            val trimmed = (if (comment < 0) line else line.substring(0, comment)).trim()
            if (trimmed.isEmpty() || trimmed.startsWith(';')) continue
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                if (section == "peer") peers++
                continue
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val rawKey = trimmed.substring(0, eqIdx).trim()
            val key = rawKey.lowercase()
            val value = trimmed.substring(eqIdx + 1).trim()
            // Phobos links write "none" for every field the panel left unset.
            if (value.equals("none", ignoreCase = true)) continue
            // The [instance] section of a Phobos .conf is the wg-obfuscator client in front of the server.
            if (section == "instance") {
                obfs_type = "wg-obfuscator"
                when (key) {
                    "key" -> obfs_key = value
                    "target" -> target = value
                    "masking" -> obfs_masking = value.uppercase().takeIf { it != "AUTO" }.orEmpty()
                    "max-dummy" -> obfs_max_dummy = QtStrings.toInt(value)
                    "obfuscate-bytes" -> obfs_obfuscate_bytes = QtStrings.toInt(value)
                    "media-pt" -> obfs_media_pt = QtStrings.toInt(value)
                    "media-ssrc" -> obfs_media_ssrc = ssrcText(value)
                    "media-clock" -> obfs_media_clock = QtStrings.toInt(value)
                }
                continue
            }
            when (key) {
                "privatekey" -> private_key = value
                // wg-quick allows the key to repeat, e.g. one line per address family.
                "address" -> address.addAll(QtStrings.splitSkipEmpty(value.replace(" ", ""), ","))
                "mtu" -> if (wireguardKeep(rawKey, value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) mtu = QtStrings.toInt(value)
                // The official clients resolve through the INI's DNS; some servers only forward what they resolved.
                "dns" -> dns = (dns + QtStrings.splitSkipEmpty(value.replace(" ", ""), ",")).toMutableList()
                "publickey" -> peer.public_key = value
                "presharedkey", "preshared_key", "psk" -> peer.pre_shared_key = value
                "persistentkeepalive", "persistent_keepalive" ->
                    if (wireguardKeep(rawKey, value) { WireGuardChecks.wireguardKeepalive(it) }) peer.persistent_keepalive = value
                "endpoint" -> {
                    val lastColon = value.lastIndexOf(':')
                    if (lastColon != -1) {
                        var host = value.substring(0, lastColon).trim()
                        if (host.startsWith('[') && host.endsWith(']')) {
                            host = if (host.length >= 2) host.substring(1, host.length - 1) else ""
                        }
                        val portText = value.substring(lastColon + 1).trim()
                        val message = Validation.port(portText)
                        if (message.isNotEmpty()) ParseDiag.error(rawKey, message, value)
                        val port = QtStrings.toInt(portText)
                        peer.address = host
                        peer.port = if (port > 0) port else 51820
                        server = peer.address
                        serverPort = peer.port
                    }
                }
                "jc" -> { jc = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "jmin" -> { jmin = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "jmax" -> { jmax = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "s1" -> { s1 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s2" -> { s2 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s3" -> { s3 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s4" -> { s4 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "h1" -> { h1 = value; enable_amnezia = true }
                "h2" -> { h2 = value; enable_amnezia = true }
                "h3" -> { h3 = value; enable_amnezia = true }
                "h4" -> { h4 = value; enable_amnezia = true }
                "i1" -> { i1 = value; enable_amnezia = true }
                "i2" -> { i2 = value; enable_amnezia = true }
                "i3" -> { i3 = value; enable_amnezia = true }
                "i4" -> { i4 = value; enable_amnezia = true }
                "i5" -> { i5 = value; enable_amnezia = true }
                "headerprotectionkey", "header_protection_key" -> { header_protection_key = value; enable_amnezia = true }
                "contentpaddingaddition", "content_padding_addition" -> { content_padding_addition = value; enable_amnezia = true }
                "rekeyaftertime", "rekey_after_time" -> { rekey_after_time = value; enable_amnezia = true }
                "rekeytimeout", "rekey_timeout" -> { rekey_timeout = value; enable_amnezia = true }
                "rejectaftertime", "reject_after_time" -> { reject_after_time = value; enable_amnezia = true }
                "keepalivetimeout", "keepalive_timeout" -> { keepalive_timeout = value; enable_amnezia = true }
                "maxhandshakeattempts", "max_handshake_attempts" -> { max_handshake_attempts = value; enable_amnezia = true }
                "randomtrailers", "random_trailers" -> { random_trailers = parseAmneziaBool(value); enable_amnezia = true }
                "disablecookies", "disable_cookies" -> { disable_cookies = parseAmneziaBool(value); enable_amnezia = true }
                "allowedips" -> allowedIps.addAll(QtStrings.splitSkipEmpty(value.replace(" ", ""), ","))
                // wg-quick's host-side settings: Rawline owns routing.
                "listenport", "table", "fwmark", "saveconfig", "preup", "postup", "predown", "postdown" -> {}
                else -> ParseDiag.warn(rawKey, vtr(R.string.validation_wg_unknown_setting))
            }
        }
        // The peer's Endpoint points at the local obfuscator; the server is its target.
        val colon = target.lastIndexOf(':')
        if (colon > 0) {
            var host = target.substring(0, colon).trim()
            if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
            peer.address = host
            peer.port = QtStrings.toInt(target.substring(colon + 1))
            server = peer.address
            serverPort = peer.port
        }
        if (peers > 1) ParseDiag.warn("[Peer]", vtr(R.string.validation_wg_peer_sections, peers.toString()))
        if (allowedIps.isNotEmpty() && !wireguardFullTunnel(allowedIps)) {
            ParseDiag.warn("AllowedIPs", vtr(R.string.validation_wg_allowed_ips_ignored), allowedIps.joinToString(", "))
        }
        // Without an MTU line the official WireGuard and AmneziaWG Android apps run at 1280; 1420 loses big packets on many paths.
        if (!Regex("""(?im)^\s*MTU\s*=""").containsMatchIn(text)) mtu = 1280
        fixAddress()
        address = wireguardDropBadAddresses(address)
        if (private_key.isEmpty() || peer.public_key.isEmpty() || (obfs_type.isNotEmpty() && obfs_key.isEmpty())) return false
        if (server.isEmpty()) {
            ParseDiag.error("Endpoint", vtr(R.string.validation_wg_no_endpoint))
            return false
        }
        return true
    }

    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy=ClashProxy(node)
        if(proxy.type!="wireguard") return false
        baseParseFromClash(proxy)
        private_key=proxy.string("private-key")
        peer=WireGuardPeer().apply {
            address=server;port=serverPort
            public_key=proxy.string("public-key")
            pre_shared_key=proxy.string("pre-shared-key")
            persistent_keepalive=proxy.string("persistent-keepalive")
            if(node.isArray("reserved")) reserved=node.array("reserved").map { JsonValues.toInt(it) }.toMutableList()
        }
        if(peer.reserved.any { it !in 0..255 }) return false
        address=(proxy.strings("ip")+proxy.strings("ipv6")).filter(String::isNotBlank).toMutableList()
        if(proxy.has("mtu")) mtu=proxy.int("mtu")
        val amnezia=JsonObject()
        val options=proxy.obj("amnezia-wg-option")
        for((key,value) in options.node) {
            val field=key.replace('-','_')
            amnezia[field]=when(field) {
                "h1","h2","h3","h4" -> options.string(key)
                "random_trailers","disable_cookies" -> options.bool(key)
                else -> value
            }
        }
        amneziaFromJson(amnezia)
        fixAddress()
        return private_key.isNotEmpty() && peer.public_key.isNotEmpty() && peer.address.isNotEmpty() &&
            peer.port in 1..65535 && address.isNotEmpty()
    }

    /** wireguard::ParseFromJson: the DB loader too, so only an import drops values. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "wireguard") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peers") && obj.isArray("peers")) {
            val peers = obj.array("peers")
            if (peers.isNotEmpty()) {
                peer.parseFromJson(peers[0] as? JsonObject ?: JsonObject())
                if (peers.size > 1 && ParseDiag.active()) {
                    ParseDiag.warn("peers", vtr(R.string.validation_wg_peers, peers.size.toString()))
                }
            }
        }
        // Listable: the core accepts a bare string as well as an array.
        if (obj.contains("address")) address = jsonStringList(obj["address"], "address")
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("workers")) worker_count = obj.int("workers")
        else if (obj.contains("worker_count")) worker_count = obj.int("worker_count")
        if (obj.contains("udp_timeout")) {
            val value = obj.string("udp_timeout").trim()
            if (wireguardKeep("udp_timeout", value, Validation.Rules.duration())) udp_timeout = value
        }
        if (obj.contains("dns")) dns = obj.array("dns").strings()
        if (obj.contains("amnezia_wg")) amneziaFromJson(obj.obj("amnezia_wg"))
        if (obj.contains("obfs")) obfsFromJson(obj.obj("obfs"))
        fixAddress()
        address = wireguardDropBadAddresses(address)
        return true
    }

    /** wireguard::ExportToLink: `wg://<peer address>:<peer port>?...`, dial items before the peer items. */
    override fun exportToLink(): String {
        // wg-obfuscator profiles travel as Phobos links, which Phobos clients import too.
        if (obfsType() == "wg-obfuscator") {
            return "phobos://" + Base64Strict.encode(exportPhobosConf().toByteArray(Charsets.UTF_8), urlSafe = true, padding = false) +
                "#" + (if (name.isEmpty()) "none" else LinkCodec.percentEncodeAll(name))
        }
        val url = LinkBuilder("wg")
        url.host = peer.address
        url.port = peer.port
        if (name.isNotEmpty()) url.fragment = name

        if (private_key.isNotEmpty()) url.addQueryItem("private_key", private_key)
        if (address.isNotEmpty()) url.addQueryItem("local_address", address.joinToString("-"))
        if (mtu > 0 && mtu != 1420) url.addQueryItem("mtu", mtu.toString())
        if (system) url.addQueryItem("use_system_interface", "true")
        if (worker_count > 0) url.addQueryItem("workers", worker_count.toString())
        if (udp_timeout.isNotEmpty()) url.addQueryItem("udp_timeout", udp_timeout)
        if (dns.isNotEmpty()) url.addQueryItem("dns", dns.joinToString(","))

        if (enable_amnezia) {
            url.addQueryItem("enable_amnezia", "true")
            if (jc > 0) url.addQueryItem("jc", jc.toString())
            if (jmin > 0) url.addQueryItem("jmin", jmin.toString())
            if (jmax > 0) url.addQueryItem("jmax", jmax.toString())
            if (s1 > 0) url.addQueryItem("s1", s1.toString())
            if (s2 > 0) url.addQueryItem("s2", s2.toString())
            if (s3 > 0) url.addQueryItem("s3", s3.toString())
            if (s4 > 0) url.addQueryItem("s4", s4.toString())
            if (h1.isNotEmpty()) url.addQueryItem("h1", h1)
            if (h2.isNotEmpty()) url.addQueryItem("h2", h2)
            if (h3.isNotEmpty()) url.addQueryItem("h3", h3)
            if (h4.isNotEmpty()) url.addQueryItem("h4", h4)
            if (i1.isNotEmpty()) url.addQueryItem("i1", i1)
            if (i2.isNotEmpty()) url.addQueryItem("i2", i2)
            if (i3.isNotEmpty()) url.addQueryItem("i3", i3)
            if (i4.isNotEmpty()) url.addQueryItem("i4", i4)
            if (i5.isNotEmpty()) url.addQueryItem("i5", i5)
            if (header_protection_key.isNotEmpty()) url.addQueryItem("header_protection_key", header_protection_key)
            if (content_padding_addition.isNotEmpty()) url.addQueryItem("content_padding_addition", content_padding_addition)
            if (rekey_after_time.isNotEmpty()) url.addQueryItem("rekey_after_time", rekey_after_time)
            if (rekey_timeout.isNotEmpty()) url.addQueryItem("rekey_timeout", rekey_timeout)
            if (reject_after_time.isNotEmpty()) url.addQueryItem("reject_after_time", reject_after_time)
            if (keepalive_timeout.isNotEmpty()) url.addQueryItem("keepalive_timeout", keepalive_timeout)
            if (max_handshake_attempts.isNotEmpty()) url.addQueryItem("max_handshake_attempts", max_handshake_attempts)
            if (random_trailers) url.addQueryItem("random_trailers", "true")
            if (disable_cookies) url.addQueryItem("disable_cookies", "true")
        }

        if (obfsType().isNotEmpty()) {
            url.addQueryItem("obfs", obfs_type)
            url.addQueryItem("obfs_key", obfs_key)
        }

        url.addQueryItems(baseLinkQuery())
        url.addQueryItems(peer.exportToLink())
        return url.build()
    }

    /** wireguard::ExportToJson: no server/server_port; the peer carries them. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.exportToJson())
        writeFields(obj, "worker_count")
        if (dns.isNotEmpty()) obj["dns"] = JsonValues.stringArray(dns)
        val peerObj = peer.exportToJson()
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return obj
    }

    /** wireguard::Build: the core's key for the worker count is `workers`. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))
        writeFields(obj, "workers")
        if (obfsType().isNotEmpty()) {
            if (enable_amnezia) return BuildResult(JsonObject(), "WireGuard obfuscation cannot be combined with AmneziaWG")
            if (obfs_key.isEmpty()) return BuildResult(JsonObject(), "WireGuard obfuscation needs a key")
        }
        val peerObj = peer.build(ctx)
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject, workersKey: String) {
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (system) obj["system"] = system
        if (worker_count > 0) obj[workersKey] = worker_count
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        val amnezia = amneziaToJson()
        if (amnezia.isNotEmpty()) obj["amnezia_wg"] = amnezia
        val obfs = obfsToJson()
        if (obfs.isNotEmpty()) obj["obfs"] = obfs
    }

    /** The first IPv4 `DNS =` resolver, or "" (shortcut: IPv6-only DNS lines are ignored, add them if a server needs it). */
    fun dnsServer(): String = dns.firstOrNull { Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it) }.orEmpty()

    /** The obfuscator in use; the editor's "off" and anything unknown are plain WireGuard. */
    fun obfsType(): String = obfs_type.takeIf { it == "wg-obfuscator" || it == "wgobfs" }.orEmpty()

    /** The core's "obfs" object; empty without an obfuscator. */
    fun obfsToJson(): JsonObject {
        val obj = JsonObject()
        if (obfsType().isEmpty()) return obj
        obj["type"] = obfs_type
        obj["key"] = obfs_key
        if (obfs_type != "wg-obfuscator") return obj
        if (obfs_masking.isNotEmpty() && obfs_masking != "NONE") obj["masking"] = obfs_masking.lowercase()
        if (obfs_max_dummy >= 0) obj["max_dummy"] = obfs_max_dummy
        if (obfs_obfuscate_bytes > 0) obj["obfuscate_bytes"] = obfs_obfuscate_bytes
        if (obfs_media_pt > 0) obj["media_pt"] = obfs_media_pt
        if (parseSsrc(obfs_media_ssrc) > 0) obj["media_ssrc"] = parseSsrc(obfs_media_ssrc)
        if (obfs_media_clock > 0) obj["media_clock"] = obfs_media_clock
        return obj
    }

    private fun obfsFromJson(obj: JsonObject) {
        obfs_type = obj.string("type")
        obfs_key = obj.string("key")
        obfs_masking = obj.string("masking").uppercase()
        obfs_max_dummy = if (obj.contains("max_dummy")) obj.int("max_dummy") else -1
        obfs_obfuscate_bytes = obj.int("obfuscate_bytes")
        obfs_media_pt = obj.int("media_pt")
        obfs_media_ssrc = ssrcText(JsonValues.toInteger(obj["media_ssrc"]).toString())
        obfs_media_clock = obj.int("media_clock")
    }

    /** The Phobos client .conf: [Interface], [Peer] and the obfuscator's [instance]. */
    fun exportPhobosConf(): String {
        val conf = arrayListOf("[Interface]", "PrivateKey = $private_key", "Address = " + address.joinToString(", "))
        if (mtu > 0) conf += "MTU = $mtu"
        conf += listOf("", "[Peer]", "PublicKey = " + peer.public_key)
        if (peer.pre_shared_key.isNotEmpty()) conf += "PresharedKey = " + peer.pre_shared_key
        conf += "AllowedIPs = 0.0.0.0/0, ::/0"
        if (peer.persistent_keepalive.isNotEmpty()) conf += "PersistentKeepalive = " + peer.persistent_keepalive
        conf += listOf("Endpoint = 127.0.0.1:13255", "", "[instance]", "source-if = 127.0.0.1", "source-lport = 13255",
            "target = " + Hosts.displayAddress(peer.address, peer.port), "key = $obfs_key", "masking = " + obfs_masking.ifEmpty { "NONE" })
        if (obfs_max_dummy >= 0) conf += "max-dummy = $obfs_max_dummy"
        if (obfs_obfuscate_bytes > 0) conf += "obfuscate-bytes = $obfs_obfuscate_bytes"
        if (obfs_media_pt > 0) conf += "media-pt = $obfs_media_pt"
        if (parseSsrc(obfs_media_ssrc) > 0) conf += "media-ssrc = " + parseSsrc(obfs_media_ssrc)
        if (obfs_media_clock > 0) conf += "media-clock = $obfs_media_clock"
        return conf.joinToString("\n") + "\n"
    }

    /** wireguard::SetAddress and friends: the peer holds the address the profile list and the DNS resolver work with. */
    override fun setAddress(newAddr: String) {
        peer.address = newAddr
    }

    override fun getAddress(): String = peer.address

    override fun setPort(newPort: Int) {
        peer.port = newPort
    }

    override fun getPort(): String = peer.port.toString()

    /** wireguard::DisplayAddress. */
    override fun displayAddress(): String = Hosts.displayAddress(peer.address, peer.port)

    /** wireguard.cpp:379-381. */
    override fun displayType(): String = if (enable_amnezia) "AWG " + amneziaVersion() else "WireGuard"

    /** The AmneziaWG generation the parameters belong to, by the markers the AmneziaVPN client itself checks. */
    fun amneziaVersion(): String = when {
        random_trailers || disable_cookies -> "3.1"
        listOf(header_protection_key, content_padding_addition, rekey_after_time, rekey_timeout, reject_after_time,
            keepalive_timeout, max_handshake_attempts).any { it.isNotBlank() } -> "3.0"
        s3 > 0 || s4 > 0 || listOf(h1, h2, h3, h4).any { '-' in it } -> "2.0"
        listOf(i1, i2, i3, i4, i5).any { it.isNotBlank() } -> "1.5"
        else -> "1.0"
    }

    /** wireguard.cpp:383-385. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", when {
        enable_amnezia -> "AmneziaWG"
        obfsType() == "wgobfs" -> "wgobfs"
        obfsType().isEmpty() -> ""
        obfs_masking.isEmpty() || obfs_masking == "NONE" -> "wg-obfuscator"
        else -> "wg-obfuscator $obfs_masking"
    }, SecurityLevel.Secure)

    /** wireguard::IsEndpoint. */
    override fun isEndpoint(): Boolean = true

    /** wireguard::Validate: key lengths and every AmneziaWG value are only checked when the endpoint first dials, so this is the early signal. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "private_key", Validation.base64Key(private_key, 32))
        issues.addIssue(Severity.Error, "public_key", Validation.base64Key(peer.public_key, 32))
        issues.addIssue(Severity.Error, "pre_shared_key", Validation.base64Key(peer.pre_shared_key, 32, empty = Empty.Allowed))
        if (peer.reserved.isNotEmpty()) {
            issues.addIssue(Severity.Error, "reserved", WireGuardChecks.wireguardReserved(peer.reserved.joinToString(",")))
        }
        issues.addIssue(
            Severity.Error, "persistent_keepalive_interval", WireGuardChecks.wireguardKeepalive(peer.persistent_keepalive),
            peer.persistent_keepalive,
        )
        if (address.isEmpty()) {
            issues.addIssue(Severity.Error, "address", vtr(R.string.validation_wg_address_required))
        } else {
            issues.addIssue(
                Severity.Error, "address",
                Validation.eachItem(address.joinToString(","), Validation.Split.Comma, Validation.Rules.cidr()),
            )
        }
        issues.addIssue(Severity.Error, "udp_timeout", Validation.duration(udp_timeout))
        if (enable_amnezia) issues.addAll(amneziaIssues())
    }

    /** wireguard::AmneziaIssues: the AmneziaWG part of [validate], keyed by amnezia_wg field; it applies only with enable_amnezia. */
    fun amneziaIssues(): MutableList<Issue> {
        val issues = ArrayList<Issue>()
        for ((field, value) in listOf("s1" to s1, "s2" to s2, "s3" to s3, "s4" to s4)) {
            // Only positive values are emitted.
            if (value > 65535) issues.addIssue(Severity.Error, field, Validation.int(value.toString(), 0, 65535))
        }
        val headers = listOf(h1, h2, h3, h4)
        for ((i, header) in headers.withIndex()) {
            issues.addIssue(Severity.Error, "h${i + 1}", WireGuardChecks.amneziaRange(header), header)
        }
        issues.addIssue(Severity.Error, "h1", WireGuardChecks.amneziaHeaders(headers))
        for ((i, signature) in listOf(i1, i2, i3, i4, i5).withIndex()) {
            issues.addIssue(Severity.Error, "i${i + 1}", WireGuardChecks.amneziaSignature(signature))
        }
        issues.addIssue(Severity.Error, "header_protection_key", Validation.base64Key(header_protection_key, 32, empty = Empty.Allowed))
        issues.addIssue(
            Severity.Error, "header_protection_key",
            WireGuardChecks.amneziaHeaderProtection(header_protection_key, listOf(s1.toLong(), s2.toLong(), s3.toLong(), s4.toLong())),
        )
        for ((field, value) in listOf(
            "content_padding_addition" to content_padding_addition, "rekey_after_time" to rekey_after_time,
            "rekey_timeout" to rekey_timeout, "reject_after_time" to reject_after_time,
            "keepalive_timeout" to keepalive_timeout, "max_handshake_attempts" to max_handshake_attempts,
        )) {
            issues.addIssue(Severity.Error, field, WireGuardChecks.amneziaRange(value), value)
        }
        issues.addIssue(Severity.Warning, "jmin", WireGuardChecks.amneziaJunkSizes(jmin.toLong(), jmax.toLong()))
        return issues
    }

    /** wireguard::AmneziaToJson. */
    private fun amneziaToJson(): JsonObject {
        val obj = JsonObject()
        if (!enable_amnezia) return obj
        if (jc > 0) obj["jc"] = jc
        if (jmin > 0) obj["jmin"] = jmin
        if (jmax > 0) obj["jmax"] = jmax
        if (s1 > 0) obj["s1"] = s1
        if (s2 > 0) obj["s2"] = s2
        if (s3 > 0) obj["s3"] = s3
        if (s4 > 0) obj["s4"] = s4
        if (h1.isNotEmpty()) obj["h1"] = h1
        if (h2.isNotEmpty()) obj["h2"] = h2
        if (h3.isNotEmpty()) obj["h3"] = h3
        if (h4.isNotEmpty()) obj["h4"] = h4
        if (i1.isNotEmpty()) obj["i1"] = i1
        if (i2.isNotEmpty()) obj["i2"] = i2
        if (i3.isNotEmpty()) obj["i3"] = i3
        if (i4.isNotEmpty()) obj["i4"] = i4
        if (i5.isNotEmpty()) obj["i5"] = i5
        if (header_protection_key.isNotEmpty()) obj["header_protection_key"] = header_protection_key
        if (content_padding_addition.isNotEmpty()) obj["content_padding_addition"] = content_padding_addition
        if (rekey_after_time.isNotEmpty()) obj["rekey_after_time"] = rekey_after_time
        if (rekey_timeout.isNotEmpty()) obj["rekey_timeout"] = rekey_timeout
        if (reject_after_time.isNotEmpty()) obj["reject_after_time"] = reject_after_time
        if (keepalive_timeout.isNotEmpty()) obj["keepalive_timeout"] = keepalive_timeout
        if (max_handshake_attempts.isNotEmpty()) obj["max_handshake_attempts"] = max_handshake_attempts
        if (random_trailers) obj["random_trailers"] = true
        if (disable_cookies) obj["disable_cookies"] = true
        return obj
    }

    /** wireguard::AmneziaFromJson: any non-empty block switches AmneziaWG on. */
    private fun amneziaFromJson(obj: JsonObject) {
        if (obj.isEmpty()) return
        enable_amnezia = true
        if (obj.contains("jc")) jc = obj.int("jc")
        if (obj.contains("jmin")) jmin = obj.int("jmin")
        if (obj.contains("jmax")) jmax = obj.int("jmax")
        if (obj.contains("s1")) s1 = obj.int("s1")
        if (obj.contains("s2")) s2 = obj.int("s2")
        if (obj.contains("s3")) s3 = obj.int("s3")
        if (obj.contains("s4")) s4 = obj.int("s4")
        if (obj.contains("h1")) h1 = obj.string("h1").trim()
        if (obj.contains("h2")) h2 = obj.string("h2").trim()
        if (obj.contains("h3")) h3 = obj.string("h3").trim()
        if (obj.contains("h4")) h4 = obj.string("h4").trim()
        if (obj.contains("i1")) i1 = obj.string("i1")
        if (obj.contains("i2")) i2 = obj.string("i2")
        if (obj.contains("i3")) i3 = obj.string("i3")
        if (obj.contains("i4")) i4 = obj.string("i4")
        if (obj.contains("i5")) i5 = obj.string("i5")
        if (obj.contains("header_protection_key")) header_protection_key = obj.string("header_protection_key")
        if (obj.contains("content_padding_addition")) content_padding_addition = amneziaRange(obj["content_padding_addition"])
        if (obj.contains("rekey_after_time")) rekey_after_time = amneziaRange(obj["rekey_after_time"])
        if (obj.contains("rekey_timeout")) rekey_timeout = amneziaRange(obj["rekey_timeout"])
        if (obj.contains("reject_after_time")) reject_after_time = amneziaRange(obj["reject_after_time"])
        if (obj.contains("keepalive_timeout")) keepalive_timeout = amneziaRange(obj["keepalive_timeout"])
        if (obj.contains("max_handshake_attempts")) max_handshake_attempts = amneziaRange(obj["max_handshake_attempts"])
        if (obj.contains("random_trailers")) random_trailers = obj.bool("random_trailers")
        if (obj.contains("disable_cookies")) disable_cookies = obj.bool("disable_cookies")
    }

    /** wireguard::AmneziaRangeFromJson: ranges are written as strings, but sing-box also accepts a bare number. */
    private fun amneziaRange(value: Any?): String = value as? String ?: JsonValues.toInt(value).toString()

    /** wireguard::FixAddress: bare IPs get /32 or /128 (the core needs a prefix); empty items are dropped. */
    fun fixAddress() {
        val fixed = ArrayList<String>(address.size)
        for (addr in address) {
            var trimmed = addr.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.contains("/")) trimmed += if (trimmed.contains(":")) "/128" else "/32"
            fixed.add(trimmed)
        }
        address = fixed
    }

    companion object {
        /** ParseAmneziaBool (wireguard.cpp). */
        @JvmStatic
        fun parseAmneziaBool(value: String): Boolean {
            val trimmed = value.trim().lowercase()
            return trimmed == "true" || trimmed == "1" || trimmed == "on" || trimmed == "yes"
        }

        /** An RTP SSRC as wg-obfuscator reads it: decimal or 0x hex, 32 bits. */
        @JvmStatic
        fun parseSsrc(value: String): Long {
            val text = value.trim().lowercase()
            val parsed = if (text.startsWith("0x")) text.substring(2).toLongOrNull(16) else text.toLongOrNull()
            return parsed?.takeIf { it in 0..0xFFFFFFFFL } ?: 0
        }

        @JvmStatic
        fun ssrcText(value: String): String = parseSsrc(value).takeIf { it > 0 }?.toString().orEmpty()
    }
}
