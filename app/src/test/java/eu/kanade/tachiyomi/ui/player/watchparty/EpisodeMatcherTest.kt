package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpisodeMatcherTest {

    private val room = EpisodeIdentity(
        animeTitle = "Frieren: Beyond Journey's End",
        mediaKey = "154587",
        mediaKeySource = "anilist",
        episodeNumber = 12.0,
        episodeTitle = "A Real Hero",
        durationMs = 1_440_000,
    )

    @Test
    fun `same tracking id and episode is exact regardless of provider`() {
        val local = EpisodeIdentity(
            animeTitle = "Sousou no Frieren",
            mediaKey = "154587",
            mediaKeySource = "anilist",
            episodeNumber = 12.0,
            durationMs = 1_421_000,
        )
        assertEquals(EpisodeMatch.EXACT, EpisodeMatcher.match(room, local))
    }

    @Test
    fun `different episode number on same show is a mismatch`() {
        val local = room.copy(episodeNumber = 11.0)
        assertEquals(EpisodeMatch.MISMATCH, EpisodeMatcher.match(room, local))
    }

    @Test
    fun `different show is a mismatch`() {
        val local = room.copy(mediaKey = "999", episodeNumber = 12.0)
        assertEquals(EpisodeMatch.MISMATCH, EpisodeMatcher.match(room, local))
    }

    @Test
    fun `title fallback tolerates provider decorations`() {
        val local = EpisodeIdentity(
            animeTitle = "frieren beyond journeys end (TV)",
            episodeNumber = 12.0,
        )
        assertEquals(EpisodeMatch.EXACT, EpisodeMatcher.match(room, local))
    }

    @Test
    fun `unknown local episode is unresolved not a mismatch`() {
        assertEquals(EpisodeMatch.UNRESOLVED, EpisodeMatcher.match(room, null))
        val empty = EpisodeIdentity(animeTitle = "", episodeNumber = -1.0)
        assertEquals(EpisodeMatch.UNRESOLVED, EpisodeMatcher.match(room, empty))
    }

    @Test
    fun `unknown episode numbers degrade to probable`() {
        val local = EpisodeIdentity(animeTitle = "Frieren: Beyond Journey's End", episodeNumber = -1.0)
        assertEquals(EpisodeMatch.PROBABLE, EpisodeMatcher.match(room, local))
    }

    @Test
    fun `tracking id from the player is used when the episode has none`() {
        val local = EpisodeIdentity(animeTitle = "Frieren", episodeNumber = 12.0)
        assertEquals(
            EpisodeMatch.EXACT,
            EpisodeMatcher.match(room, local, localMediaKey = "154587", localMediaKeySource = "anilist"),
        )
    }

    @Test
    fun `title similarity handles common provider decorations`() {
        assertTrue(EpisodeMatcher.titlesSimilar("Attack on Titan", "Attack on Titan Season 2"))
        assertTrue(EpisodeMatcher.titlesSimilar("Mob Psycho 100 II", "Mob Psycho 100 2"))
        assertTrue(EpisodeMatcher.titlesSimilar("Oshi no Ko", "【Oshi no Ko】"))
        assertFalse(EpisodeMatcher.titlesSimilar("Attack on Titan", "Bleach"))
        assertFalse(EpisodeMatcher.titlesSimilar("One Piece", "One Punch Man"))
    }

    @Test
    fun `very short titles do not match by substring`() {
        assertFalse(EpisodeMatcher.titlesSimilar("K", "K-On"))
    }

    @Test
    fun `title normalisation strips qualifiers`() {
        assertEquals("attack on titan", EpisodeMatcher.normalizeTitle("Attack on Titan (TV)"))
        assertEquals("mob psycho 100 2", EpisodeMatcher.normalizeTitle("Mob Psycho 100 II"))
    }
}
