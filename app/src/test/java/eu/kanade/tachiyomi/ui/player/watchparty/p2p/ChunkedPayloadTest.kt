package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkedPayloadTest {

    private val fingerprint = (1..32).joinToString(":") { "%02X".format(it) }

    private fun realPayload(): String = PairingPayload.encode(
        PairingPayload.Payload(
            role = PairingPayload.Role.OFFER,
            sessionId = "session-abcdef123456",
            roomId = "42:anime/solo-leveling",
            ufrag = "ufragXyz",
            pwd = "pwdLongEnoughValue123",
            fingerprint = fingerprint,
            candidates = listOf(
                PairingPayload.IceCandidate(PairingPayload.IceCandidate.Type.HOST, "192.168.1.5", 54321),
            ),
            expiresAtMs = 1_000_000L,
        ),
    )

    @Test
    fun `a payload that fits stays one code`() {
        val payload = realPayload()
        val chunks = ChunkedPayload.split(payload, "session-abcdef123456")
        assertEquals(1, chunks.size)
        assertEquals(payload, chunks.single().body)
    }

    @Test
    fun `a single chunk round trips through render and parse`() {
        val payload = realPayload()
        val chunk = ChunkedPayload.split(payload, "session-abcdef123456").single()
        val parsed = ChunkedPayload.parse(chunk.render())!!
        assertEquals(chunk, parsed)
    }

    @Test
    fun `a long payload is split and reassembles exactly`() {
        val payload = realPayload() + "".padEnd(1500, 'Z')
        val chunks = ChunkedPayload.split(payload, "session-abcdef123456", maxChunkChars = 400)
        assertTrue(chunks.size > 1)

        val assembler = ChunkedPayload.Assembler()
        var completed: String? = null
        chunks.forEach { chunk ->
            when (val progress = assembler.accept(ChunkedPayload.parse(chunk.render())!!)) {
                is ChunkedPayload.Progress.Complete -> completed = progress.payload
                else -> Unit
            }
        }
        assertEquals(payload, completed)
    }

    @Test
    fun `chunks are balanced rather than one giant plus a sliver`() {
        val payload = "x".repeat(900)
        val chunks = ChunkedPayload.split(payload, "session-abcdef123456", maxChunkChars = 300)
        val sizes = chunks.map { it.body.length }
        assertTrue(sizes.max() - sizes.min() <= 1, "unbalanced chunk sizes: $sizes")
        assertEquals(payload, chunks.joinToString("") { it.body })
    }

    @Test
    fun `fragments can be scanned in any order`() {
        val payload = realPayload() + "".padEnd(1000, 'Q')
        val chunks = ChunkedPayload.split(payload, "session-abcdef123456", maxChunkChars = 300)
        val assembler = ChunkedPayload.Assembler()
        var completed: String? = null
        chunks.reversed().forEach { chunk ->
            when (val progress = assembler.accept(chunk)) {
                is ChunkedPayload.Progress.Complete -> completed = progress.payload
                is ChunkedPayload.Progress.Partial -> assertTrue(progress.have <= progress.total)
                is ChunkedPayload.Progress.Rejected -> throw AssertionError(progress.reason)
            }
        }
        assertEquals(payload, completed)
    }

    @Test
    fun `progress is reported so the scanner can show a counter`() {
        val chunks = ChunkedPayload.split("y".repeat(900), "session-abcdef123456", maxChunkChars = 300)
        val assembler = ChunkedPayload.Assembler()
        val first = assembler.accept(chunks[0]) as ChunkedPayload.Progress.Partial
        assertEquals(1, first.have)
        assertEquals(chunks.size, first.total)
    }

    @Test
    fun `a fragment from a different invitation restarts instead of corrupting`() {
        val a = ChunkedPayload.Chunk("session-aaaaaaaa", 0, 2, "AAA")
        val b = ChunkedPayload.Chunk("session-bbbbbbbb", 0, 2, "BBB")
        val assembler = ChunkedPayload.Assembler()
        assembler.accept(a)
        val progress = assembler.accept(b)
        // Buffer was reset for the new session rather than mixing the two.
        assertEquals(1, (progress as ChunkedPayload.Progress.Partial).have)
        assertEquals(2, progress.total)
    }

    @Test
    fun `mismatched totals for the same session are rejected`() {
        val assembler = ChunkedPayload.Assembler()
        assembler.accept(ChunkedPayload.Chunk("session-aaaaaaaa", 0, 2, "AAA"))
        val progress = assembler.accept(ChunkedPayload.Chunk("session-aaaaaaaa", 1, 3, "BBB"))
        assertTrue(progress is ChunkedPayload.Progress.Rejected)
    }

    @Test
    fun `a re-scanned fragment does not double count`() {
        val chunks = ChunkedPayload.split("z".repeat(900), "session-abcdef123456", maxChunkChars = 300)
        val assembler = ChunkedPayload.Assembler()
        assembler.accept(chunks[0])
        val again = assembler.accept(chunks[0]) as ChunkedPayload.Progress.Partial
        assertEquals(1, again.have)
    }

    @Test
    fun `reset forgets the partial payload`() {
        val assembler = ChunkedPayload.Assembler()
        assembler.accept(ChunkedPayload.Chunk("session-aaaaaaaa", 0, 2, "AAA"))
        assembler.reset()
        assertEquals(0, assembler.have)
        assertEquals(0, assembler.expected)
    }

    @Test
    fun `a chunk is never mistaken for a complete payload`() {
        val payload = realPayload() + "".padEnd(1000, 'K')
        val chunk = ChunkedPayload.split(payload, "session-abcdef123456", maxChunkChars = 300).first()
        assertNull(PairingPayload.decode(chunk.render()))
        assertNotNull(ChunkedPayload.parse(chunk.render()))
    }

    @Test
    fun `a complete payload is never mistaken for a chunk`() {
        assertNull(ChunkedPayload.parse(realPayload()))
    }

    @Test
    fun `non chunk text is rejected quietly`() {
        assertNull(ChunkedPayload.parse(null))
        assertNull(ChunkedPayload.parse(""))
        assertNull(ChunkedPayload.parse("https://example.com"))
        assertNull(ChunkedPayload.parse("HP1C.session.0"))       // no body
        assertNull(ChunkedPayload.parse("HP1C.session.0/2."))    // empty body
        assertNull(ChunkedPayload.parse("HP1C.session.x/2.body")) // bad index
        assertNull(ChunkedPayload.parse("HP1C.session.2/2.body")) // index out of range
        assertNull(ChunkedPayload.parse("HP1C.session.0/99.body")) // beyond MAX_CHUNKS
    }

    @Test
    fun `too many chunks is refused rather than generating an unscannable set`() {
        val huge = "w".repeat(ChunkedPayload.MAX_CHUNKS * 100 + 1)
        assertTrue(ChunkedPayload.split(huge, "session-abcdef123456", maxChunkChars = 100).isEmpty())
    }
}
