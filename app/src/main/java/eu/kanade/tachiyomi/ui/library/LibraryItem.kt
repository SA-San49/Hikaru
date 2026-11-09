package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.getNameForAnimeInfo
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy

private val sourceManager: SourceManager by injectLazy()

/** A parsed query so per-item search does not re-split comma-separated constraints. */
internal class LibrarySearchQuery(private val text: String) {

    private val metadataConstraints = text.split(",").map { it.trim() }

    fun matches(item: LibraryItem): Boolean {
        val anime = item.libraryAnime.anime
        return anime.title.contains(text, true) ||
            (anime.author?.contains(text, true) ?: false) ||
            (anime.artist?.contains(text, true) ?: false) ||
            (anime.description?.contains(text, true) ?: false) ||
            metadataConstraints.all(item::matchesMetadataConstraint)
    }
}

data class LibraryItem(
    val libraryAnime: LibraryAnime,
    val downloadCount: Long = -1,
    val unseenCount: Long = -1,
    val isLocal: Boolean = false,
    val sourceLanguage: String = "",
    val showSourceIcon: Boolean = false,
    val showLanguageIcon: Boolean = false,
    val domainSource: tachiyomi.domain.source.model.Source? = null,
) {
    val source by lazy { sourceManager.getOrStub(libraryAnime.anime.source) }
    val sourceName by lazy { source.getNameForAnimeInfo() }

    fun matches(constraint: String): Boolean = LibrarySearchQuery(constraint).matches(this)

    internal fun matches(query: LibrarySearchQuery): Boolean = query.matches(this)

    internal fun matchesMetadataConstraint(constraint: String): Boolean {
        val isNegated = constraint.startsWith("-")
        val value = if (isNegated) constraint.substringAfter("-").trimStart() else constraint
        val matches = sourceName.contains(value, true) ||
            (libraryAnime.anime.genre?.any { it.equals(value, true) } ?: false)
        return if (isNegated) !matches else matches
    }
}
