package tachiyomi.source.localanime.io

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

data class LocalFileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long,
) {
    val extension: String
        get() {
            if (isDirectory) return ""
            val dotIndex = name.lastIndexOf('.')
            return if (dotIndex > 0 && dotIndex < name.lastIndex) {
                name.substring(dotIndex + 1)
            } else {
                ""
            }
        }

    val nameWithoutExtension: String
        get() {
            if (isDirectory) return name
            val dotIndex = name.lastIndexOf('.')
            return if (dotIndex > 0) name.substring(0, dotIndex) else name
        }
}

actual class LocalAnimeSourceFileSystem(
    private val storageManager: StorageManager,
) {

    private class CachedListing(
        val entries: List<LocalFileEntry>,
        val timestamp: Long,
    )

    private val context: Context
        get() = storageManager.appContext

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val baseLock = Any()
    private val animeCacheLock = Any()
    private val subDirCacheLock = Any()

    @Volatile
    private var baseCache: CachedListing? = null

    private val animeDirCache = LruCache<String, CachedListing>(ANIME_DIR_CACHE_MAX_ENTRIES)
    private val subDirCache = LruCache<String, CachedListing>(SUB_DIR_CACHE_MAX_ENTRIES)

    init {
        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        return listBaseDirectoryEntries().mapNotNull { toUniFile(it.uri) }
    }

    actual fun getAnimeDirectory(name: String): UniFile? {
        val entry = findAnimeDirEntry(name) ?: return null
        return toUniFile(entry.uri)
    }

    actual fun getFilesInAnimeDirectory(name: String): List<UniFile> {
        return listAnimeDirectoryEntries(name).mapNotNull { toUniFile(it.uri) }
    }

    fun invalidateCache() {
        storageManager.invalidateLocalSourceDirectoryCache()
        synchronized(baseLock) {
            baseCache = null
        }
        synchronized(animeCacheLock) {
            animeDirCache.evictAll()
        }
        synchronized(subDirCacheLock) {
            subDirCache.evictAll()
        }
    }

    fun invalidateAnimeCache(animeName: String) {
        synchronized(animeCacheLock) {
            animeDirCache.remove(animeName)
        }
        val prefix = "$animeName/"
        synchronized(subDirCacheLock) {
            val snapshot = subDirCache.snapshot()
            for (key in snapshot.keys) {
                if (key.startsWith(prefix)) {
                    subDirCache.remove(key)
                }
            }
        }
    }

    fun listBaseDirectoryEntries(forceRefresh: Boolean = false): List<LocalFileEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            baseCache?.let { cached ->
                if ((now - cached.timestamp) < CACHE_TTL_MS) {
                    return cached.entries
                }
            }
        }
        return synchronized(baseLock) {
            val currentNow = System.currentTimeMillis()
            if (!forceRefresh) {
                baseCache?.let { cached ->
                    if ((currentNow - cached.timestamp) < CACHE_TTL_MS) {
                        return@synchronized cached.entries
                    }
                }
            }
            val baseDir = getBaseDirectory() ?: return@synchronized emptyList()
            val entries = queryDirectoryEntries(baseDir.uri)
            baseCache = CachedListing(entries, System.currentTimeMillis())
            entries
        }
    }

    fun findAnimeDirEntry(name: String): LocalFileEntry? {
        val cachedMatch = listBaseDirectoryEntries().firstOrNull { it.isDirectory && it.name == name }
        if (cachedMatch != null) return cachedMatch
        val directDir = getBaseDirectory()?.findFile(name)?.takeIf { it.isDirectory } ?: return null
        return LocalFileEntry(
            uri = directDir.uri,
            name = directDir.name ?: name,
            isDirectory = true,
            lastModified = directDir.lastModified(),
            size = 0L,
        )
    }

    fun listAnimeDirectoryEntries(name: String, forceRefresh: Boolean = false): List<LocalFileEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(animeCacheLock) {
                animeDirCache.get(name)?.let { cached ->
                    if ((now - cached.timestamp) < CACHE_TTL_MS) {
                        return cached.entries
                    }
                }
            }
        }
        val dirEntry = findAnimeDirEntry(name) ?: return emptyList()
        val entries = queryDirectoryEntries(dirEntry.uri)
        synchronized(animeCacheLock) {
            animeDirCache.put(name, CachedListing(entries, System.currentTimeMillis()))
        }
        return entries
    }

    fun listSubDirectoryEntries(
        animeName: String,
        subDirEntry: LocalFileEntry,
        forceRefresh: Boolean = false,
    ): List<LocalFileEntry> {
        val cacheKey = "$animeName/${subDirEntry.name}"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(subDirCacheLock) {
                subDirCache.get(cacheKey)?.let { cached ->
                    if ((now - cached.timestamp) < CACHE_TTL_MS) {
                        return cached.entries
                    }
                }
            }
        }
        val entries = queryDirectoryEntries(subDirEntry.uri)
        synchronized(subDirCacheLock) {
            subDirCache.put(cacheKey, CachedListing(entries, System.currentTimeMillis()))
        }
        return entries
    }

    fun findEpisodeEntry(animeName: String, episodeRelativePath: String): LocalFileEntry? {
        val slashIndex = episodeRelativePath.indexOf('/')
        val topEntries = listAnimeDirectoryEntries(animeName)
        if (slashIndex < 0) {
            return topEntries.firstOrNull { !it.isDirectory && it.name == episodeRelativePath }
        }
        val subDirName = episodeRelativePath.substring(0, slashIndex)
        val fileName = episodeRelativePath.substring(slashIndex + 1)
        val subDir = topEntries.firstOrNull { it.isDirectory && it.name == subDirName } ?: return null
        return listSubDirectoryEntries(animeName, subDir)
            .firstOrNull { !it.isDirectory && it.name == fileName }
    }

    fun findEpisodeByNameWithoutExtension(animeName: String, episodeName: String): LocalFileEntry? {
        val topEntries = listAnimeDirectoryEntries(animeName)
        topEntries.firstOrNull {
            !it.isDirectory && Archive.isSupportedExtension(it.extension) && it.nameWithoutExtension == episodeName
        }?.let { return it }

        for (entry in topEntries) {
            if (!entry.isDirectory || entry.name.startsWith('.')) continue
            listSubDirectoryEntries(animeName, entry).firstOrNull {
                !it.isDirectory && Archive.isSupportedExtension(it.extension) && it.nameWithoutExtension == episodeName
            }?.let { return it }
        }
        return null
    }

    fun getEpisodeFileSize(animeName: String, episodeRelativePath: String): Long? {
        return findEpisodeEntry(animeName, episodeRelativePath)?.size
    }

    fun findCoverUriInDirectory(animeName: String, dirUri: Uri): Uri? {
        val now = System.currentTimeMillis()
        val cachedEntries = synchronized(animeCacheLock) {
            animeDirCache.get(animeName)?.takeIf { (now - it.timestamp) < CACHE_TTL_MS }?.entries
        }
        if (cachedEntries != null) {
            return cachedEntries.firstOrNull { !it.isDirectory && it.name.lowercase() in COVER_FILENAMES }?.uri
        }

        if (dirUri.scheme == "file") {
            val dir = dirUri.path?.let { File(it) } ?: return null
            for (coverName in COVER_FILENAMES_ORDERED) {
                val candidate = File(dir, coverName)
                if (candidate.isFile) return Uri.fromFile(candidate)
            }
            return dir.listFiles()?.firstOrNull {
                it.isFile && it.name.lowercase() in COVER_FILENAMES
            }?.let { Uri.fromFile(it) }
        }

        if (dirUri.scheme == "content" && DocumentsContract.isTreeUri(dirUri)) {
            return queryCoverUriSaf(dirUri)
        }

        return queryDirectoryEntries(dirUri)
            .firstOrNull { !it.isDirectory && it.name.lowercase() in COVER_FILENAMES }
            ?.uri
    }

    fun toUniFile(uri: Uri): UniFile? {
        return UniFile.fromUri(context, uri)
    }

    private fun queryCoverUriSaf(dirUri: Uri): Uri? {
        val parentDocId = resolveDocumentId(dirUri) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentDocId)
        return try {
            context.contentResolver.query(
                childrenUri,
                SAF_COVER_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIdx < 0 || nameIdx < 0) return@use null

                while (cursor.moveToNext()) {
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val displayName = cursor.getString(nameIdx) ?: continue
                    if (displayName.lowercase() in COVER_FILENAMES) {
                        val docId = cursor.getString(idIdx) ?: continue
                        return@use DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDirectoryEntries(dirUri: Uri): List<LocalFileEntry> {
        if (dirUri.scheme == "file") {
            return queryDirectoryEntriesPosix(dirUri)
        }
        if (dirUri.scheme == "content" && DocumentsContract.isTreeUri(dirUri)) {
            queryDirectoryEntriesSaf(dirUri)?.let { return it }
        }
        return queryDirectoryEntriesUniFileFallback(dirUri)
    }

    private fun queryDirectoryEntriesPosix(dirUri: Uri): List<LocalFileEntry> {
        val path = dirUri.path ?: return emptyList()
        val files = File(path).listFiles() ?: return emptyList()
        val result = ArrayList<LocalFileEntry>(files.size)
        for (file in files) {
            val isDir = file.isDirectory
            result.add(
                LocalFileEntry(
                    uri = Uri.fromFile(file),
                    name = file.name,
                    isDirectory = isDir,
                    lastModified = file.lastModified(),
                    size = if (isDir) 0L else file.length(),
                ),
            )
        }
        return result
    }

    private fun queryDirectoryEntriesSaf(dirUri: Uri): List<LocalFileEntry>? {
        val parentDocId = resolveDocumentId(dirUri) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentDocId)
        return try {
            context.contentResolver.query(
                childrenUri,
                SAF_CHILD_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idIdx < 0 || nameIdx < 0) return@use null

                val result = ArrayList<LocalFileEntry>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val displayName = cursor.getString(nameIdx) ?: continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val lastMod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                    val fileSize = if (!isDir && sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        cursor.getLong(sizeIdx)
                    } else {
                        0L
                    }
                    result.add(
                        LocalFileEntry(
                            uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId),
                            name = displayName,
                            isDirectory = isDir,
                            lastModified = lastMod,
                            size = fileSize,
                        ),
                    )
                }
                result
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDirectoryEntriesUniFileFallback(dirUri: Uri): List<LocalFileEntry> {
        val files = UniFile.fromUri(context, dirUri)?.listFiles() ?: return emptyList()
        val result = ArrayList<LocalFileEntry>(files.size)
        for (file in files) {
            val fileName = file.name ?: continue
            val isDir = file.isDirectory
            result.add(
                LocalFileEntry(
                    uri = file.uri,
                    name = fileName,
                    isDirectory = isDir,
                    lastModified = file.lastModified(),
                    size = if (isDir) 0L else file.length(),
                ),
            )
        }
        return result
    }

    private fun resolveDocumentId(dirUri: Uri): String? {
        return try {
            if (DocumentsContract.isDocumentUri(context, dirUri)) {
                DocumentsContract.getDocumentId(dirUri)
            } else {
                DocumentsContract.getTreeDocumentId(dirUri)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val ANIME_DIR_CACHE_MAX_ENTRIES = 64
        private const val SUB_DIR_CACHE_MAX_ENTRIES = 64

        val COVER_FILENAMES = hashSetOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
        )

        private val COVER_FILENAMES_ORDERED = listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
        )

        private val SAF_CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        private val SAF_COVER_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
