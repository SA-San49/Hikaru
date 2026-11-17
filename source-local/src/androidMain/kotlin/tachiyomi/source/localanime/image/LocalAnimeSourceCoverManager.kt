package tachiyomi.source.localanime.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

actual class LocalAnimeSourceCoverManager(
    private val context: Context,
    private val fileSystem: LocalAnimeSourceFileSystem,
) {

    actual fun find(animeUrl: String): UniFile? {
        val animeDir = fileSystem.getAnimeDirectory(animeUrl) ?: return null
        return findInDirectory(animeDir)
    }

    actual fun findInDirectory(animeDir: UniFile): UniFile? {
        val files = animeDir.listFiles() ?: return null
        return files.firstOrNull { file ->
            when (file.name?.lowercase()) {
                "cover.jpg", "cover.jpeg", "cover.png", "cover.webp" -> true
                else -> false
            }
        }
    }

    actual fun update(anime: SAnime, inputStream: InputStream): UniFile? {
        val directory = fileSystem.getAnimeDirectory(anime.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = findInDirectory(directory) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        anime.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
