package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.OpenVpn
import app.rawline.mobile.ui.ValidationForm
import app.rawline.mobile.validation.Validation

/**
 * OpenVPN endpoint: the primary fields on the screen, the full option set through the Raw JSON preference. The
 * desktop's Advanced dialog and table checks run in OpenVpn.validate; issues of fields without a row are listed at save.
 */
class OpenVpnSettingsActivity : BindingSettingsActivity<OpenVpn>() {

    override fun createEntity() = OpenVpn()
    override val preferencesResource = R.xml.openvpn_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("network")
        pbm.text("username")
        pbm.text("password")
        pbm.text("static_challenge")
        pbm.int("mtu")
        pbm.text("tls.server_name")
        pbm.text("tls.certificate")
        pbm.text("tls.client_certificate")
        pbm.text("tls.client_key")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("mtu")
        passwordSummary("password", "static_challenge")
        multilineInput("tls.certificate", "tls.client_certificate", "tls.client_key")
    }

    /** edit_openvpn.cpp bindValidation: everything else it binds is checked by OpenVpn.validate. */
    override fun ValidationForm.bindValidation() {
        bind("mtu", Validation.Rules.int(0, 65535))
    }

}
