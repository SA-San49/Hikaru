package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Room code handling.
 *
 * Room ids double as the signalling topic, so they must be short, easy to type
 * and read aloud, and hard to guess: 8 characters from a 32 symbol alphabet
 * (just under 40 bits) with visually ambiguous characters removed.
 */
object RoomCode {

    /**
     * Characters that survive being read over voice chat.
     *
     * Excludes `0/O`, `1/I` and `L`, because those are the pairs people
     * mistype when copying a code by hand or reading it aloud.
     */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    const val LENGTH = 8

    fun generate(random: () -> Int = { kotlin.random.Random.nextInt() }): String {
        val builder = StringBuilder(LENGTH)
        repeat(LENGTH) {
            builder.append(ALPHABET[Math.floorMod(random(), ALPHABET.length)])
        }
        return builder.toString()
    }

    /** Normalises user input: uppercase, strips spaces/dashes, validates length. */
    fun normalize(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != LENGTH) return null
        if (cleaned.any { it !in ALPHABET }) return null
        return cleaned
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /** Formats a code for display: `AB12 CD34`. */
    fun format(code: String): String {
        val normalized = normalize(code) ?: return code
        return normalized.chunked(4).joinToString(" ")
    }

    /** Human-friendly invite link understood by [WatchPartyInviteParser]. */
    fun inviteLink(code: String): String = "https://hikaru.app/watchparty/$code"

    /** True when [first] and [second] name the same room. */
    fun same(first: String, second: String): Boolean {
        val a = normalize(first) ?: return false
        val b = normalize(second) ?: return false
        return a == b
    }
}
