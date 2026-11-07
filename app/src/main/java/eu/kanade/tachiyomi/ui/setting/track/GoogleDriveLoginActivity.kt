package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GoogleDriveLoginActivity : BaseOAuthLoginActivity() {
    private val googleDriveService = Injekt.get<GoogleDriveService>()
    override fun handleResult(data: Uri?) {
        // The callback is reachable by any app on the device, so reject anything that does not
        // echo the single-use state value issued when this app started the sign-in attempt.
        if (!googleDriveService.consumeOAuthState(data?.getQueryParameter("state"))) {
            Toast.makeText(
                this@GoogleDriveLoginActivity,
                stringResource(SYMR.strings.google_drive_login_failed, "invalid or expired sign-in attempt"),
                Toast.LENGTH_LONG,
            ).show()

            returnToSettings()
            return
        }

        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")
        if (code != null) {
            lifecycleScope.launchIO {
                googleDriveService.handleAuthorizationCode(
                    code,
                    this@GoogleDriveLoginActivity,
                    onSuccess = {
                        Toast.makeText(
                            this@GoogleDriveLoginActivity,
                            stringResource(SYMR.strings.google_drive_login_success),
                            Toast.LENGTH_LONG,
                        ).show()

                        returnToSettings()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@GoogleDriveLoginActivity,
                            stringResource(SYMR.strings.google_drive_login_failed, error),
                            Toast.LENGTH_LONG,
                        ).show()
                        returnToSettings()
                    },
                )
            }
        } else if (error != null) {
            Toast.makeText(
                this@GoogleDriveLoginActivity,
                stringResource(SYMR.strings.google_drive_login_failed, error),
                Toast.LENGTH_LONG,
            ).show()

            returnToSettings()
        } else {
            returnToSettings()
        }
    }
}
