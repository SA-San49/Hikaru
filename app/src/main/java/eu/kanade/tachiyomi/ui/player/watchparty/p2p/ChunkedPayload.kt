package eu.kanade.tachiyomi.ui.player.watchparty.p2p

/**
 * Splits a pairing payload across several QR codes, and puts it back together.
 *
 * A compressed payload is usually one QR code, but not always: a phone that
 * could only gather relay candidates, or a room id for an unusually long URL,
 * can push past what a single code can hold at a reliable density. Rather than
 * silently generating an unscannable code, the payload is cut into numbered
 * chunks.
 *
 * Design constraints:
 *
 *  - **Each chunk is independently well-formed text.** No binary framing, so a
 *    partially-scanned set is never mistaken for a corrupt whole.
 *  - **Every chunk carries the total count and the session id.** A guest who
 *    scans an old code from a different attempt is rejected instead of stitched
 *    into a Frankenstein payload.
 *  - **Chunks are scanned in any order.** People scan what is easiest first;
 *    the assembler buffers and reports progress.
 *  - **Reassembly has a hard bound.** A malicious or broken screen could
 *    otherwise make a guest accumulate unbounded memory.
 *
 * Wire format, one per code:
 *
 * ```
 * HP1C.<sessionId>.<index>/<total>.<body>
 * ```
 *
 * `HP1C` is the chunk magic. It is intentionally *not* accepted by
 * [PairingPayload.decode], so an incomplete chunk can never be parsed as a
 * complete invitation.
 */
object ChunkedPayload {

    /** Magic for a fragment. Distinct from [PairingPayload.MAGIC] by design. */
    const val MAGIC = "HP1C"

    /** Upper bound on fragments for one invitation. */
    const val MAX_CHUNKS = 16

    /** Upper bound on a single fragment's body, in characters. */
    const val MAX_CHUNK_CHARS = 600

    /** One fragment as it appears on screen. */
    data class Chunk(
        val sessionId: String,
        val index: Int,
        val total: Int,
        val body: String,
    ) {
        /** Text to encode into the QR image. */
        fun render(): String = "$MAGIC.$sessionId.$index/$total.$body"
    }

    /** Result of adding a scanned fragment. */
    sealed interface Progress {
        /** Fragment accepted, more needed. */
        data class Partial(val have: Int, val total: Int) : Progress

        /** Last fragment arrived; [payload] is the reassembled invitation text. */
        data class Complete(val payload: String) : Progress

        /** Fragment was not accepted; [reason] is shown to the user. */
        data class Rejected(val reason: String) : Progress
    }

    /**
     * Cuts [payload] into up to [MAX_CHUNKS] fragments.
     *
     * Returns a single-element list when it already fits, so callers do not need
     * to special-case the common path.
     */
    fun split(payload: String, sessionId: String, maxChunkChars: Int = MAX_CHUNK_CHARS): List<Chunk> {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }

        if (payload.length <= maxChunkChars) {
            return listOf(Chunk(sessionId, index = 0, total = 1, body = payload))
        }

        val total = (payload.length + maxChunkChars - 1) / maxChunkChars
        if (total > MAX_CHUNKS) return emptyList()

        // Prefer balanced chunks over one huge part plus a tiny remainder: each
        // QR then has a similar density, which scans far more reliably.
        val perChunk = (payload.length + total - 1) / total
        return payload.chunked(perChunk).mapIndexed { index, body ->
            Chunk(sessionId = sessionId, index = index, total = total, body = body)
        }
    }

    /**
     * Parses one scanned fragment.
     *
     * Returns `null` when the text is not a fragment at all, so a camera
     * constantly seeing unrelated codes does not spam errors.
     */
    fun parse(raw: String?): Chunk? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("$MAGIC.")) return null
        val rest = trimmed.removePrefix("$MAGIC.")

        val sessionEnd = rest.indexOf('.')
        if (sessionEnd <= 0) return null
        val sessionId = rest.substring(0, sessionEnd)

        val headerEnd = rest.indexOf('.', sessionEnd + 1)
        if (headerEnd <= sessionEnd + 1) return null
        val header = rest.substring(sessionEnd + 1, headerEnd)

        val body = rest.substring(headerEnd + 1)
        if (body.length > MAX_CHUNK_CHARS) return null

        val parts = header.split('/')
        if (parts.size != 2) return null
        val index = parts[0].toIntOrNull() ?: return null
        val total = parts[1].toIntOrNull() ?: return null
        if (total !in 1..MAX_CHUNKS) return null
        if (index !in 0 until total) return null
        if (body.isEmpty()) return null

        return Chunk(sessionId = sessionId, index = index, total = total, body = body)
    }

    /**
     * Accumulates fragments until a whole payload is available.
     *
     * Not thread safe on purpose: it is driven by the scanner callback on a
     * single thread.
     */
    class Assembler {
        private var sessionId: String? = null
        private var total: Int = 0
        private val bodies = HashMap<Int, String>()

        /** Fragments currently buffered. */
        val have: Int get() = bodies.size

        /** Total needed for the current session, `0` before the first fragment. */
        val expected: Int get() = total

        /** Forgets everything; call when the scanner closes or restarts. */
        fun reset() {
            sessionId = null
            total = 0
            bodies.clear()
        }

        /**
         * Adds a scanned fragment.
         *
         * A fragment from a different session clears the buffer first: two
         * people scanning at once must not corrupt one another's result.
         */
        fun accept(chunk: Chunk): Progress {
            if (chunk.total > MAX_CHUNKS) {
                return Progress.Rejected("This invitation has too many parts.")
            }
            if (sessionId != null && sessionId != chunk.sessionId) {
                // A new invitation started; the old partial is worthless.
                reset()
            }
            if (sessionId == null) {
                sessionId = chunk.sessionId
                total = chunk.total
            } else if (total != chunk.total) {
                return Progress.Rejected("These codes are from different invitations.")
            }

            bodies[chunk.index] = chunk.body

            if (bodies.size < total) {
                return Progress.Partial(have = bodies.size, total = total)
            }

            val assembled = buildString {
                for (i in 0 until total) {
                    val body = bodies[i] ?: return Progress.Partial(have = bodies.size, total = total)
                    append(body)
                }
            }
            reset()
            return Progress.Complete(assembled)
        }
    }
}
