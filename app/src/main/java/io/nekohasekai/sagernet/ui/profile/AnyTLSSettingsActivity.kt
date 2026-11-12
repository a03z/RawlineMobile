package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.AnyTls
import app.rawline.mobile.ui.ValidationForm
import app.rawline.mobile.validation.Validation

class AnyTLSSettingsActivity : BindingSettingsActivity<AnyTls>() {

    override fun createEntity() = AnyTls()
    override val preferencesResource = R.xml.anytls_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("password")
        pbm.text("idle_session_check_interval")
        pbm.text("idle_session_timeout")
        pbm.int("min_idle_session")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("min_idle_session")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
    }

    // edit_anytls.cpp: text the binding would read as 0
    override fun ValidationForm.bindValidation() {
        bind("min_idle_session", Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
    }

}
