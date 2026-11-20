package eu.kanade.tachiyomi.util.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.domain.ui.model.AppIcon
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object AppIconManager {

    fun setAppIcon(context: Context, appIcon: AppIcon) {
        val packageManager = context.packageManager
        val packageName = context.packageName

        AppIcon.entries.forEach { icon ->
            val isSelected = icon == appIcon
            val componentName = ComponentName(packageName, "$packageName${icon.activityName}")
            val desiredState = if (isSelected) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            try {
                val currentState = packageManager.getComponentEnabledSetting(componentName)
                if (currentState != desiredState) {
                    packageManager.setComponentEnabledSetting(
                        componentName,
                        desiredState,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "Failed to set component state for ${icon.activityName}"
                }
            }
        }
    }
}
