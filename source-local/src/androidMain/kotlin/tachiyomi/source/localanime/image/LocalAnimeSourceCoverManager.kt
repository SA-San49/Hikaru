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
        val coverEntry = fileSystem.listAnimeDirectoryEntries(animeUrl)
            .firstOrNull { !it.isDirectory && it.name.lowercase() in LocalAnimeSourceFileSystem.COVER_FILENAMES }
            ?: return null
        return fileSystem.toUniFile(coverEntry.uri)
    }

    actual fun findInDirectory(animeDir: UniFile): UniFile? {
        val animeName = animeDir.name
        if (!animeName.isNullOrEmpty()) {
            val coverUri = fileSystem.findCoverUriInDirectory(animeName, animeDir.uri)
            if (coverUri != null) {
                return fileSystem.toUniFile(coverUri)
            }
        }
        val files = animeDir.listFiles() ?: return null
        return files.firstOrNull { file ->
            file.name?.lowercase() in LocalAnimeSourceFileSystem.COVER_FILENAMES
        }
    }

    actual fun update(anime: SAnime, inputStream: InputStream): UniFile? {
        val directory = fileSystem.getAnimeDirectory(anime.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(anime.url) ?: directory.createFile(DEFAULT_COVER_NAME)
        if (targetFile == null) {
            inputStream.close()
            return null
        }

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)
        anime.thumbnail_url = targetFile.uri.toString()
        fileSystem.invalidateAnimeCache(anime.url)
        return targetFile
    }
}
