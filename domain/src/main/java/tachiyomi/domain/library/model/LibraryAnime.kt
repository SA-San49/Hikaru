package tachiyomi.domain.library.model

import tachiyomi.domain.anime.model.Anime

data class LibraryAnime(
    val anime: Anime,
    /** All categories this anime belongs to. */
    val categories: List<Long>,
    /** Category represented by this row in a category-scoped library list. */
    val categoryId: Long,
    val totalEpisodes: Long,
    val seenCount: Long,
    val bookmarkCount: Long,
    // AM (FILLERMARK) -->
    val fillermarkCount: Long,
    // <-- AM (FILLERMARK)
    val latestUpload: Long,
    val episodeFetchedAt: Long,
    val lastSeen: Long,
    val folderId: Long? = null,
) {
    val id: Long = anime.id

    val unseenCount
        get() = totalEpisodes - seenCount

    val hasBookmarks
        get() = bookmarkCount > 0

    // AM (FILLERMARK) -->
    val hasFillermarks
        get() = fillermarkCount > 0
    // <-- AM (FILLERMARK)

    val hasStarted = seenCount > 0
}
