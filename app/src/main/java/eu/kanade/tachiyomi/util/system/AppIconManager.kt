package eu.kanade.tachiyomi.util.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.domain.ui.model.AppIcon
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object AppIconManager {

    /**
     * Enables the selected launcher activity-alias first, then disables all other aliases.
     * Uses [Context.getPackageName] as the package (matches applicationId across all build
     * variants) paired with [AppIcon.componentClassName] (matches manifest namespace).
     */
    fun setAppIcon(context: Context, appIcon: AppIcon): Boolean {
        val packageManager = context.packageManager
        val packageName = context.packageName

        val targetComponent = ComponentName(packageName, appIcon.componentClassName)
        try {
            val targetState = packageManager.getComponentEnabledSetting(targetComponent)
            if (targetState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                packageManager.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } catch (e: IllegalArgumentException) {
            logcat(LogPriority.ERROR, e) {
                "Failed to enable launcher icon alias ${appIcon.componentClassName}"
            }
            return false
        } catch (e: SecurityException) {
            logcat(LogPriority.ERROR, e) {
                "Permission error enabling launcher icon alias ${appIcon.componentClassName}"
            }
            return false
        }

        AppIcon.entries
            .filter { it != appIcon }
            .forEach { otherIcon ->
                val otherComponent = ComponentName(packageName, otherIcon.componentClassName)
                try {
                    val currentState = packageManager.getComponentEnabledSetting(otherComponent)
                    if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        packageManager.setComponentEnabledSetting(
                            otherComponent,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    logcat(LogPriority.ERROR, e) {
                        "Failed to disable launcher icon alias ${otherIcon.componentClassName}"
                    }
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e) {
                        "Permission error disabling launcher icon alias ${otherIcon.componentClassName}"
                    }
                }
            }

        return true
    }

    /**
     * Resolves whichever launcher activity-alias is currently enabled in PackageManager.
     */
    fun getActiveAppIcon(context: Context): AppIcon {
        val packageManager = context.packageManager
        val packageName = context.packageName

        val explicitlyEnabled = AppIcon.entries.firstOrNull { icon ->
            try {
                val component = ComponentName(packageName, icon.componentClassName)
                packageManager.getComponentEnabledSetting(component) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        return explicitlyEnabled ?: AppIcon.DEFAULT
    }
}
