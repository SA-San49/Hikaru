package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchPartyInviteParserTest {

    @Test
    fun `bare and formatted codes are accepted`() {
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("AB34CD56"))
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("ab34 cd56"))
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("  AB34CD56\n"))
    }

    @Test
    fun `invite links are accepted`() {
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("https://hikaru.app/watchparty/AB34CD56"))
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("hikaru://watchparty?room=AB34CD56"))
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode("hikaru://watchparty/AB34CD56"))
    }

    @Test
    fun `codes embedded in chat text are found`() {
        val text = "Join my Hikaru Watch Party! Room code: AB34 CD56 — see you there"
        assertEquals("AB34CD56", WatchPartyInviteParser.parseRoomCode(text))
    }

    @Test
    fun `invalid input yields null instead of throwing`() {
        assertNull(WatchPartyInviteParser.parseRoomCode(null))
        assertNull(WatchPartyInviteParser.parseRoomCode(""))
        assertNull(WatchPartyInviteParser.parseRoomCode("hello world"))
        assertNull(WatchPartyInviteParser.parseRoomCode("https://example.com/watchparty/AB34CD56"))
        assertNull(WatchPartyInviteParser.parseRoomCode("hikaru://watchparty?room=%ZZ"))
    }

    @Test
    fun `invite text contains code and episode`() {
        val text = WatchPartyInviteParser.inviteText(
            "AB34CD56",
            EpisodeIdentity(animeTitle = "Frieren", episodeNumber = 3.0, episodeTitle = "The Journey"),
        )
        assertTrue(text.contains("AB34 CD56"))
        assertTrue(text.contains("https://hikaru.app/watchparty/AB34CD56"))
        assertTrue(text.contains("Episode 3"))
    }

    @Test
    fun `invite text works without an episode`() {
        assertTrue(WatchPartyInviteParser.inviteText("AB34CD56").contains("anime"))
    }
}
