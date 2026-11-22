package eu.kanade.tachiyomi.util.storage

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.hippo.unifile.UniFile

fun Uri.toFFmpegString(context: Context): String {
    return if (this.scheme == "content") {
        FFmpegKitConfig.getSafParameter(context, this, "rw")
    } else {
        this.path!!
    }.replace("\"", "\\\"")
}

fun UniFile.toFFmpegString(context: Context? = null): String {
    return if (context != null && this.uri.scheme == "content") {
        FFmpegKitConfig.getSafParameter(context, this.uri, "rw")
    } else {
        this.filePath!!
    }.replace("\"", "\\\"")
}

fun Uri.toFFmpegReadString(context: Context): String? {
    val resolved = if (this.scheme == "content") {
        FFmpegKitConfig.getSafParameterForRead(context, this)
    } else {
        this.path
    }
    return resolved?.replace("\"", "\\\"")
}

fun UniFile.toFFmpegReadString(context: Context): String? {
    val resolved = if (this.uri.scheme == "content") {
        FFmpegKitConfig.getSafParameterForRead(context, this.uri)
    } else {
        this.filePath
    }
    return resolved?.replace("\"", "\\\"")
}
