package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomCodeTest {

    @Test
    fun `generated codes are valid and normalise to themselves`() {
        repeat(200) {
            val code = RoomCode.generate()
            assertEquals(RoomCode.LENGTH, code.length)
            assertEquals(code, RoomCode.normalize(code))
            assertTrue(RoomCode.isValid(code))
        }
    }

    @Test
    fun `generated codes exclude ambiguous characters`() {
        val generated = (1..500).map { RoomCode.generate() }.joinToString("")
        listOf('0', 'O', '1', 'I', 'L').forEach { banned ->
            assertFalse(generated.contains(banned), "code contained ambiguous char $banned")
        }
    }

    @Test
    fun `normalize accepts lowercase, spaces and dashes`() {
        assertEquals("AB34CD56", RoomCode.normalize("ab34cd56"))
        assertEquals("AB34CD56", RoomCode.normalize(" AB34 CD56 "))
        assertEquals("AB34CD56", RoomCode.normalize("AB34-CD56"))
    }

    @Test
    fun `normalize rejects wrong length and invalid characters`() {
        assertNull(RoomCode.normalize(""))
        assertNull(RoomCode.normalize("ABC"))
        assertNull(RoomCode.normalize("AB34CD565"))
        // '0' and 'O' are not in the alphabet.
        assertNull(RoomCode.normalize("AB02CD34"))
        assertNull(RoomCode.normalize("ABO2CD34"))
    }

    @Test
    fun `formatting and comparison are stable`() {
        assertEquals("AB34 CD56", RoomCode.format("ab34cd56"))
        assertTrue(RoomCode.same("ab34cd56", "AB34 CD56"))
        assertFalse(RoomCode.same("ab34cd56", "AB34CD57"))
    }

    @Test
    fun `invite link round trips`() {
        val code = RoomCode.generate()
        assertTrue(RoomCode.inviteLink(code).endsWith(code))
    }
}
