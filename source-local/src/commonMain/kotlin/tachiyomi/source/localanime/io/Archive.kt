package tachiyomi.source.localanime.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension

object Archive {
    private val SUPPORTED_VIDEO_TYPES = hashSetOf(
        "avi",
        "flv",
        "mkv",
        "mov",
        "mp4",
        "m4v",
        "webm",
        "wmv",
        "ts",
        "mts",
        "m2ts",
        "3gp",
        "mpg",
        "mpeg",
        "ogm",
        "ogv",
    )

    private val SUPPORTED_PLAYLIST_TYPES = hashSetOf(
        "torrent",
        "m3u",
        "m3u8",
    )

    private val SUPPORTED_ARCHIVE_TYPES = HashSet<String>(
        SUPPORTED_VIDEO_TYPES.size + SUPPORTED_PLAYLIST_TYPES.size,
    ).apply {
        addAll(SUPPORTED_VIDEO_TYPES)
        addAll(SUPPORTED_PLAYLIST_TYPES)
    }

    fun isSupported(file: UniFile): Boolean {
        return isSupportedExtension(file.extension)
    }

    fun isSupportedName(fileName: String?): Boolean {
        if (fileName.isNullOrEmpty()) return false
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex == fileName.lastIndex) return false
        return isSupportedExtension(fileName.substring(dotIndex + 1))
    }

    fun isSupportedExtension(extension: String?): Boolean {
        if (extension.isNullOrEmpty()) return false
        return extension.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }

    fun isDirectVideoExtension(extension: String?): Boolean {
        if (extension.isNullOrEmpty()) return false
        return extension.lowercase() in SUPPORTED_VIDEO_TYPES
    }
}
