package app.rawline.mobile.ui.settings

import android.os.Build
import androidx.preference.Preference
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.ktx.launchCustomTab
import app.rawline.mobile.ktx.runOnLifecycleDispatcher
import app.rawline.mobile.ktx.onMainDispatcher
import app.rawline.mobile.ktx.snackbar
import app.rawline.mobile.ui.MobileAlertDialogBuilder
import app.rawline.mobile.update.UpdateChecker

/** Version, sources and the GPL text, as GPL-3.0 asks an interactive program to show. */
class AboutSettingsFragment : SettingsScreenFragment(R.xml.settings_about) {
    override fun bind() {
        pref<Preference>("aboutVersion").apply {
            summary = SagerNet.appVersionNameForDisplay
            setOnPreferenceClickListener { requireContext().launchCustomTab(UpdateChecker.RELEASES_PAGE); true }
        }
        pref<Preference>("aboutCore").apply {
            val ref = BuildConfig.THRONE_CORE_REF
            summary = if (ref.length == 40 && ref.all { it in '0'..'9' || it in 'a'..'f' }) ref.take(8) else ref
            setOnPreferenceClickListener {
                val copied = SagerNet.trySetPrimaryClip(ref)
                // Android 13+ confirms a clipboard copy itself
                if (!copied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                    snackbar(if (copied) R.string.grp_copied else R.string.action_export_err).show()
                true
            }
        }
        pref<Preference>("aboutSource").setOnPreferenceClickListener {
            requireContext().launchCustomTab("https://github.com/ttrroyy/RawlineMobile"); true
        }
        pref<Preference>("aboutBase").setOnPreferenceClickListener {
            requireContext().launchCustomTab("https://github.com/throneproj/ThroneForAndroid"); true
        }
        pref<Preference>("aboutLicense").setOnPreferenceClickListener {
            val context = requireContext()
            runOnLifecycleDispatcher {
                val text = context.assets.open("LICENSE").bufferedReader().use { it.readText() }
                onMainDispatcher {
                    MobileAlertDialogBuilder(context).setTitle(R.string.license)
                        .setMessage(text).setPositiveButton(android.R.string.ok, null).show()
                }
            }
            true
        }
    }
}
