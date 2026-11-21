package tachiyomi.domain.release.model

import android.os.Build

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    private val assets: List<String>,
    // KMK -->
    val preRelease: Boolean = false,
    val draft: Boolean = false,
    // KMK <--
) {

    /**
     * Get download link of latest release from the assets.
     * @return download link of latest release.
     */
    fun getDownloadLink(): String {
        val apkVariant = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64-v8a"
            "armeabi-v7a" -> "armeabi-v7a"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> ""
        }

        val apkAssets = assets.filter { it.endsWith(".apk", ignoreCase = true) }.ifEmpty { assets }
        return apkAssets.find { apkVariant.isNotEmpty() && it.contains(apkVariant, ignoreCase = true) }
            ?: apkAssets.firstOrNull()
            ?: releaseLink
    }

    /**
     * Assets class containing download url.
     */
    data class Assets(val downloadLink: String)
}
