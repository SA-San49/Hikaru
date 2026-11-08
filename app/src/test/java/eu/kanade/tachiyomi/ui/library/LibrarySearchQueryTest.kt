package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.library.model.LibraryAnime
import eu.kanade.tachiyomi.source.model.UpdateStrategy

class LibrarySearchQueryTest {

    @Test
    fun `matches title and author case insensitively`() {
        val item = LibraryItem(
            libraryAnime = LibraryAnime(
                anime = Anime(
                    id = 1L,
                    source = 1L,
                    favorite = false,
                    lastUpdate = 0L,
                    nextUpdate = 0L,
                    fetchInterval = 0,
                    dateAdded = 0L,
                    viewerFlags = 0L,
                    episodeFlags = 0L,
                    coverLastModified = 0L,
                    url = "https://example.test/anime/1",
                    ogTitle = "Naruto",
                    ogArtist = null,
                    ogAuthor = "Masashi Kishimoto",
                    ogThumbnailUrl = null,
                    ogDescription = null,
                    ogGenre = null,
                    ogStatus = 0L,
                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                    initialized = false,
                    lastModifiedAt = 0L,
                    favoriteModifiedAt = null,
                    version = 0L,
                ),
                category = 0L,
                totalEpisodes = 0L,
                seenCount = 0L,
                bookmarkCount = 0L,
                fillermarkCount = 0L,
                latestUpload = 0L,
                episodeFetchedAt = 0L,
                lastSeen = 0L,
            ),
        )

        assertTrue(LibrarySearchQuery("naruto").matches(item))
        assertTrue(LibrarySearchQuery("kishimoto").matches(item))
    }
}
