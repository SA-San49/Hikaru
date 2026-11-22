package tachiyomi.source.localanime.image

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import tachiyomi.source.localanime.io.LocalFileEntry
import java.io.InputStream

private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"

actual class LocalAnimeSourceEpisodeThumbnailManager(
    private val context: Context,
    private val fileSystem: LocalAnimeSourceFileSystem,
) {

    actual fun find(animeUrl: String, episodeName: String): UniFile? {
        val entries = fileSystem.listAnimeDirectoryEntries(animeUrl)
        val imageMap = buildImageLookup(entries)
        val uri = findUriInLookup(imageMap, episodeName) ?: return null
        return fileSystem.toUniFile(uri)
    }

    fun buildImageLookup(entries: List<LocalFileEntry>): Map<String, Uri> {
        val map = HashMap<String, Uri>()
        for (entry in entries) {
            if (!entry.isDirectory && entry.extension.lowercase() in IMAGE_EXTENSIONS) {
                val key = entry.nameWithoutExtension.lowercase()
                if (key !in map) {
                    map[key] = entry.uri
                }
            }
        }
        return map
    }

    fun findUriInLookup(imageMap: Map<String, Uri>, episodeName: String): Uri? {
        if (imageMap.isEmpty()) return null
        val lowerName = episodeName.lowercase()
        return imageMap["$lowerName-thumbnail"]
            ?: imageMap[lowerName]
            ?: imageMap["$lowerName-cover"]
    }

    actual fun update(anime: SAnime, episode: SEpisode, inputStream: InputStream): UniFile? {
        val directory = fileSystem.getAnimeDirectory(anime.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val fileName = "${episode.name}-$DEFAULT_THUMBNAIL_NAME"
        val targetFile = find(anime.url, episode.name) ?: directory.createFile(fileName)
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
        episode.preview_url = targetFile.uri.toString()
        fileSystem.invalidateAnimeCache(anime.url)
        return targetFile
    }

    companion object {
        private val IMAGE_EXTENSIONS = hashSetOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "avif",
            "gif",
            "bmp",
        )
    }
}
