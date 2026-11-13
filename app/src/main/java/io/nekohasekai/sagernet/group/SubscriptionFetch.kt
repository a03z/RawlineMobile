package app.rawline.mobile.group

import androidx.core.net.toUri
import app.rawline.mobile.R
import app.rawline.mobile.database.SubHttpVersion
import app.rawline.mobile.database.SubUserInfo
import app.rawline.mobile.ktx.HttpGetOptions
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.httpGet
import app.rawline.mobile.ktx.readableMessage
import java.io.ByteArrayOutputStream

/** GroupUpdater::fetch (GroupUpdater.cpp:448-463), plus the Android `content://` subscription URLs. */
object SubscriptionFetch {

    /** kMaxSubscriptionBytes (GroupUpdater.cpp:24). */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** The body of an HTTP error status is logged up to this size. */
    private const val MAX_LOGGED_BODY = 4096

    /**
     * [error] is null on success; [subInfo] is what the headers and the body's head said ([SubscriptionMetadata]);
     * [routing] is the trimmed `routing` header (GroupUpdater.cpp fetch), "" when absent.
     */
    class Result(
        @JvmField val body: String,
        @JvmField val subInfo: SubUserInfo,
        @JvmField val error: String?,
        @JvmField val routing: String = "",
    )

    fun fetch(url: String, rawName: String, identity: RequestIdentity): Result {
        // ImportUrl names the request by its URL, whose userinfo is the Basic auth credential.
        val name = withoutUserInfo(rawName)
        val encrypted = ProviderLinks.isEncrypted(url)
        Logs.i(">>>>>>>> " + app.getString(R.string.subs_requesting, name))
        val result = try {
            val resolved = ProviderLinks.decodeChecked(url)?.data ?: url
            if (!encrypted && resolved.startsWith("content://", ignoreCase = true)) readContent(resolved)
            else {
                require(SubscriptionCrypto.isHttpUrl(resolved))
                request(resolved, identity)
            }
        } catch (_: Exception) {
            Result("", SubUserInfo(), app.getString(R.string.provider_crypto_import_error))
        }
        if (result.error != null) {
            if (encrypted) {
                // HTTP clients can include the decoded URL in exceptions and providers can echo it in error bodies.
                val safeError = app.getString(R.string.provider_crypto_fetch_error)
                Logs.w("<<<<<<<< " + app.getString(R.string.subs_request_error, name, safeError))
                return Result("", SubUserInfo(), safeError)
            }
            val reason = withoutUrls(result.error, url)
            Logs.w("<<<<<<<< " + app.getString(R.string.subs_request_error, name, reason + "\n" + withoutUrls(result.body, url)))
            return Result("", SubUserInfo(), reason)
        }
        Logs.i("<<<<<<<< " + app.getString(R.string.subs_request_finished, name))
        if (result.subInfo.serverInterval > 0) {
            Logs.i(app.getString(R.string.subs_server_interval, result.subInfo.serverInterval))
        }
        return result
    }

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""")

    private val USER_INFO = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)[^/?#\s]*@""")

    private fun withoutUserInfo(text: String): String = USER_INFO.replace(text, "$1")

    /** withoutUrls (GroupUpdater.cpp): an HTTP error text can embed the request or redirect URL, and with it the subscription token. */
    private fun withoutUrls(text: String, url: String): String {
        fun hostOf(u: String): String = runCatching { u.toUri().host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "\u2026"
        var redacted = text
        if (url.isNotEmpty()) redacted = redacted.replace(url, hostOf(url))
        return URL.replace(redacted) { hostOf(it.value) }
    }

    private fun request(url: String, identity: RequestIdentity): Result {
        val result = requestAs(url, identity)
        // Some subscription proxies (p.kfwl.lol) serve a web page to a client they do not know; with the app's own
        // agent (not one the user set) the request is made once more as the widespread v2rayNG.
        if (result.error == null && identity.userAgent.startsWith("Happ/3.0 ") && looksLikeWebPage(result.body)) {
            Logs.i("The server returned a web page; requesting the subscription again as another client")
            return requestAs(url, RequestIdentity("v2rayNG/1.10.16", identity.sendHwid, identity.device, identity.timeoutSeconds,
                identity.sendHwidInCookie, identity.allowInsecureRequests))
        }
        return result
    }

    private fun looksLikeWebPage(body: String): Boolean {
        val head = body.trimStart().take(16).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun requestAs(url: String, identity: RequestIdentity): Result {
        val response = httpGet(
            url,
            HttpGetOptions(
                userAgent = identity.userAgent,
                headers = identity.headers(),
                maxBytes = MAX_BYTES,
                timeoutSeconds = identity.timeoutSeconds,
                allowInsecure = identity.allowInsecureRequests,
                tlsVersion = identity.tlsVersion,
                http2 = identity.httpVersion != SubHttpVersion.HTTP11,
            ),
        )
        if (!response.ok) {
            val body = String(response.data, 0, minOf(response.data.size, MAX_LOGGED_BODY), Charsets.UTF_8)
            return Result(body, SubUserInfo(), response.error)
        }
        val body = try {
            SubscriptionCrypto.decryptResponse(response.finalUrl.ifBlank { url }, String(response.data, Charsets.UTF_8), response.header("Encrypt-Tag"), url)
        } catch (_: Exception) {
            return Result("", SubUserInfo(), app.getString(R.string.provider_crypto_response_error))
        }
        return Result(body, SubscriptionMetadata.read(response::header, body), null, response.header(SubscriptionRoute.HEADER).trim())
    }

    private fun readContent(url: String): Result = try {
        val input = app.contentResolver.openInputStream(url.toUri()) ?: error("cannot open $url")
        val out = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) error(app.getString(R.string.subs_response_too_large, MAX_BYTES / (1024 * 1024)))
            }
        }
        val body = out.toString(Charsets.UTF_8.name())
        Result(body, SubscriptionMetadata.read({ "" }, body), null)
    } catch (e: Exception) {
        Result("", SubUserInfo(), e.readableMessage)
    }
}
