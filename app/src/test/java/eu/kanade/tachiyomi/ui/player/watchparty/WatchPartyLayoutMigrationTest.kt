package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchPartyLayoutMigrationTest {

    @Test
    fun `the button is inserted right after its anchor`() {
        assertEquals(
            "LockControls,ScreenRotation,PlaybackSpeed,WatchParty,CurrentChapter",
            WatchPartyLayoutMigration.migrateRegion(
                "LockControls,ScreenRotation,PlaybackSpeed,CurrentChapter",
                anchor = "PlaybackSpeed",
            ),
        )
    }

    @Test
    fun `an existing WatchParty entry is never duplicated`() {
        assertNull(
            WatchPartyLayoutMigration.migrateRegion(
                "PlaybackSpeed,WatchParty,CurrentChapter",
                anchor = "PlaybackSpeed",
            ),
        )
    }

    @Test
    fun `a region without its anchor is left alone`() {
        // The user removed this part of the layout on purpose.
        assertNull(
            WatchPartyLayoutMigration.migrateRegion(
                "LockControls,CurrentChapter",
                anchor = "PlaybackSpeed",
            ),
        )
    }

    @Test
    fun `an empty region is left alone`() {
        assertNull(WatchPartyLayoutMigration.migrateRegion("", anchor = "PlaybackSpeed"))
        assertNull(WatchPartyLayoutMigration.migrateRegion("  ,  ", anchor = "PlaybackSpeed"))
    }

    @Test
    fun `unknown button names are preserved in place`() {
        assertEquals(
            "QualityTracks,WatchParty,MoreOptions,SomeFutureButton",
            WatchPartyLayoutMigration.migrateRegion(
                "QualityTracks,MoreOptions,SomeFutureButton",
                anchor = "QualityTracks",
            ),
        )
    }

    @Test
    fun `every configured region has its own anchor`() {
        assertEquals("QualityTracks", WatchPartyLayoutMigration.anchorFor("pref_top_right_controls"))
        assertEquals("PlaybackSpeed", WatchPartyLayoutMigration.anchorFor("pref_bottom_left_controls"))
        assertEquals("PlaybackSpeed", WatchPartyLayoutMigration.anchorFor("pref_portrait_bottom_controls"))
        assertNull(WatchPartyLayoutMigration.anchorFor("pref_something_else"))
        assertEquals(3, WatchPartyLayoutMigration.keys.size)
    }
}
