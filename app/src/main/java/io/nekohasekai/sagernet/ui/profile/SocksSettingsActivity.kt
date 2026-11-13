package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.outbound.types.SocksChecks
import app.rawline.mobile.ui.ValidationForm
import app.rawline.mobile.ui.json.ProfileJson
import app.rawline.mobile.validation.Severity

/** The version menu offers 5, 4 and 4a (version 4 with socks4a), as edit_socks.cpp. */
class SocksSettingsActivity : BindingSettingsActivity<Socks>() {

    override fun createEntity() = Socks()
    override val preferencesResource = R.xml.socks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("uot")
        pbm.text("phobos_key")
        pbm.text("phobos_masking")
        pbm.text("phobos_media_ssrc")
    }

    override fun Socks.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        DataStore.profileCacheStore.putString("version", if (version != 4) "5" else if (socks4a) "4a" else "4")
    }

    override fun Socks.serialize() {
        pbm.fromCacheAll(this)
        version = menuVersion()
        socks4a = menuText() == "4a"
    }

    private fun menuText() = DataStore.profileCacheStore.getString("version")?.trim().orEmpty()

    private fun menuVersion() = if (menuText().startsWith("4")) 4 else 5

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password", "phobos_key")
        onMenu("version") { setVisible(it != "4", "password") }
    }

    override fun ValidationForm.bindValidation() {
        addRule(listOf("password", "username", "version"), Severity.Warning) {
            val store = DataStore.profileCacheStore
            SocksChecks.socksAuth(menuVersion(), store.getString("username").orEmpty().trim(), store.getString("password").orEmpty())
        }
    }

}
