package eu.kanade.presentation.more.settings.screen.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.watchparty.WatchPartyPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerSettingsAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_player_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val advancedPlayerPreferences = remember { Injekt.get<AdvancedPlayerPreferences>() }
        val watchPartyPreferences = remember { Injekt.get<WatchPartyPreferences>() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val enableScripts = advancedPlayerPreferences.mpvScripts()
        val mpvConf = advancedPlayerPreferences.mpvConf()
        val mpvInput = advancedPlayerPreferences.mpvInput()

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                title = stringResource(MR.strings.pref_mpv_scripts),
                subtitle = stringResource(MR.strings.pref_mpv_scripts_summary),
                pref = enableScripts,
                onValueChanged = {
                    // Ask for external storage permission
                    if (it) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.fromParts("package", context.packageName, null)
                            context.startActivity(intent)
                        }
                    }
                    true
                },
            ),
            Preference.PreferenceItem.MPVConfPreference(
                pref = mpvConf,
                title = stringResource(MR.strings.pref_mpv_conf),
                fileName = "mpv.conf",
                scope = scope,
                context = context,
            ),
            Preference.PreferenceItem.MPVConfPreference(
                pref = mpvInput,
                title = stringResource(MR.strings.pref_mpv_input),
                fileName = "input.conf",
                scope = scope,
                context = context,
            ),
            // Watch Party: optional feature, off the playback path until used.
            Preference.PreferenceItem.SwitchPreference(
                pref = watchPartyPreferences.enabled(),
                title = stringResource(MR.strings.pref_watch_party_enabled),
                subtitle = stringResource(MR.strings.pref_watch_party_enabled_summary),
            ),
            Preference.PreferenceItem.EditTextPreference(
                pref = watchPartyPreferences.displayName(),
                title = stringResource(MR.strings.pref_watch_party_display_name),
                subtitle = stringResource(MR.strings.pref_watch_party_display_name_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                pref = watchPartyPreferences.floatingReactions(),
                title = stringResource(MR.strings.pref_watch_party_floating_reactions),
                subtitle = stringResource(MR.strings.pref_watch_party_floating_reactions_summary),
            ),
            Preference.PreferenceItem.EditTextPreference(
                pref = watchPartyPreferences.relayUrl(),
                title = stringResource(MR.strings.pref_watch_party_relay),
                subtitle = stringResource(MR.strings.pref_watch_party_relay_summary),
            ),
        )
    }
}
