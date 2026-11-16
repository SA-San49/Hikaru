package eu.kanade.tachiyomi.ui.home.genre

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.Anime
import eu.kanade.tachiyomi.source.model.UpdateStrategy

class GenreFeedUnitTest {

    @Test
    fun `normalizes genre keys and display names correctly`() {
        assertEquals("action", GenreNormalizer.toKey("Action"))
        assertEquals("action", GenreNormalizer.toKey("  actions  "))
        assertEquals("sci-fi", GenreNormalizer.toKey("Science Fiction"))
        assertEquals("sci-fi", GenreNormalizer.toKey("Sci-Fi"))
        assertEquals("slice of life", GenreNormalizer.toKey("Slice Of Life"))
        assertEquals("martial arts", GenreNormalizer.toKey("Martial   Arts"))

        assertEquals("Action", GenreNormalizer.toDisplayName("action"))
        assertEquals("Sci-Fi", GenreNormalizer.toDisplayName("sci-fi"))
        assertEquals("Slice of Life", GenreNormalizer.toDisplayName("slice of life"))
        assertEquals("Martial Arts", GenreNormalizer.toDisplayName("martial arts"))
    }

    @Test
    fun `deduplicates anime inside a genre row by normalized title`() {
        fun makeAnime(id: Long, title: String): Anime {
            return Anime(
                id = id,
                source = 1L,
                favorite = false,
                lastUpdate = 0L,
                nextUpdate = 0L,
                fetchInterval = 0,
                dateAdded = 0L,
                viewerFlags = 0L,
                episodeFlags = 0L,
                coverLastModified = 0L,
                url = "https://example.com/$id",
                ogTitle = title,
                ogArtist = null,
                ogAuthor = null,
                ogThumbnailUrl = null,
                ogDescription = null,
                ogGenre = null,
                ogStatus = 0L,
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                initialized = false,
                lastModifiedAt = 0L,
                favoriteModifiedAt = null,
                version = 0L,
            )
        }

        val items = listOf(
            makeAnime(1L, "Attack on Titan"),
            makeAnime(2L, "attack on titan!"),
            makeAnime(3L, "Attack  On  Titan"),
            makeAnime(4L, "Demon Slayer: Kimetsu no Yaiba"),
            makeAnime(5L, "demon slayer kimetsu no yaiba"),
            makeAnime(6L, "One Piece"),
        )

        val deduped = TitleDeduplicator.dedupeAnime(items)
        assertEquals(3, deduped.size)
        assertEquals("Attack on Titan", deduped[0].title)
        assertEquals(1L, deduped[0].id)
        assertEquals("Demon Slayer: Kimetsu no Yaiba", deduped[1].title)
        assertEquals(4L, deduped[1].id)
        assertEquals("One Piece", deduped[2].title)
        assertEquals(6L, deduped[2].id)
    }

    @Test
    fun `detects genre filter keywords heuristic`() {
        assertTrue(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Genres"))
        assertTrue(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Anime Genre"))
        assertTrue(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Tags"))
        assertTrue(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Themes"))
        assertTrue(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Demographic"))

        assertFalse(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Order By"))
        assertFalse(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Status"))
        assertFalse(GenreDiscoveryEngine.isGenreLikeGroupOrFilterName("Season"))
    }

    @Test
    fun `applies genre filter on group check box`() {
        class TestCheck(name: String) : AnimeFilter.CheckBox(name, false)
        class TestGroup(name: String, items: List<TestCheck>) : AnimeFilter.Group<TestCheck>(name, items)

        val actionCheck = TestCheck("Action")
        val comedyCheck = TestCheck("Comedy")
        val filterList = AnimeFilterList(listOf(TestGroup("Genres", listOf(actionCheck, comedyCheck))))

        val applier = GenreFilterApplier.GroupCheckBox("Genres", "Action")
        val success = applier.apply(filterList)

        assertTrue(success)
        assertTrue(actionCheck.state)
        assertFalse(comedyCheck.state)
    }

    @Test
    fun `applies genre filter on select filter`() {
        class TestSelect(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values, 0)

        val select = TestSelect("Genre", arrayOf("All", "Action", "Romance"))
        val filterList = AnimeFilterList(listOf(select))

        val applier = GenreFilterApplier.SelectOption("Genre", 1, "Action")
        val success = applier.apply(filterList)

        assertTrue(success)
        assertEquals(1, select.state)
    }
}
