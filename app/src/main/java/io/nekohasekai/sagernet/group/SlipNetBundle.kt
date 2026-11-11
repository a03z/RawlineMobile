package app.rawline.mobile.group

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SlipNet's "Export All (Encrypted)": slipnet-bundle-enc:// + base64 of [0x01][salt 16][iv 12][AES-256-GCM
 * ciphertext+tag], the key PBKDF2-HMAC-SHA256 of the user's password, 600000 rounds (its BundleCrypto). The plain text
 * is its usual export, imported as it is.
 */
object SlipNetBundle {
    const val SCHEME = "slipnet-bundle-enc://"
    private val LINK = Regex("slipnet-bundle-enc://[A-Za-z0-9+/=_-]+", RegexOption.IGNORE_CASE)

    fun links(text: String): List<String> = LINK.findAll(text).map { it.value }.toList()

    /** The bundle's text, or null for a wrong password or a damaged bundle. Slow by design: off the main thread. */
    fun decrypt(link: String, password: String): String? = runCatching {
        val payload = link.substring(SCHEME.length).trim()
        val data = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: Base64.decode(payload, Base64.URL_SAFE)
        require(data.size >= 1 + 16 + 12 + 16 && data[0] == 1.toByte())
        val salt = data.copyOfRange(1, 17)
        val iv = data.copyOfRange(17, 29)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 600_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data.copyOfRange(29, data.size)), Charsets.UTF_8)
    }.getOrNull()
}
