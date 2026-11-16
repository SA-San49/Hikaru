package eu.kanade.tachiyomi.ui.player.watchparty

import java.net.URLDecoder

/**
 * Parses Watch Party invitation text and links.
 *
 * Accepts:
 *  - bare room codes:            `AB12CD34`
 *  - formatted codes:            `AB12 CD34`
 *  - invite links:               `https://hikaru.app/watchparty/AB12CD34`
 *  - app deep links:             `hikaru://watchparty?room=AB12CD34`
 *  - pasted chat text/captions:  `Join my Hikaru Watch Party: AB12CD34`
 */
object WatchPartyInviteParser {

    private val linkPattern = Regex(
        """(?:https?://hikaru\.app/watchparty/|hikaru://watchparty(?:/|\?room=))([0-9A-Za-z]{4,16})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * An 8-character run that is not part of a longer word.
     *
     * `\b` is avoidable: a code glued to punctuation ("Room code: AB34CD56 —")
     * is still a code, but it must not be carved out of a longer token. The
     * lookarounds express exactly that, and [RoomCode.normalize] still has the
     * final say on whether the characters are valid.
     */
    private val codePattern = Regex("""(?<![0-9A-Za-z])([0-9A-Za-z]{8})(?![0-9A-Za-z])""")

    /** Two four-character halves separated by a single space, e.g. "AB34 CD56". */
    private val splitCodePattern = Regex("""(?<![0-9A-Za-z])([0-9A-Za-z]{4})[ -]([0-9A-Za-z]{4})(?![0-9A-Za-z])""")

    /** Any URL-ish run, used to avoid mining codes out of someone else's link. */
    private val urlPattern = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+|\bwww\.\S+|\b[\w-]+(?:\.[\w-]+)+/\S*""")

    /** Returns the room code encoded in [input], or `null` when there is none. */
    fun parseRoomCode(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        linkPattern.find(trimmed)?.let { match ->
            return RoomCode.normalize(match.groupValues[1])
        }

        // Deep link with a query payload (hikaru://watchparty?room=CODE) may arrive
        // percent-encoded from a browser or another app.
        if (trimmed.contains("room=", ignoreCase = true)) {
            val encoded = trimmed.substringAfter("room=").substringBefore('&')
            runCatching { URLDecoder.decode(encoded, "UTF-8") }
                .getOrNull()
                ?.let { decoded -> RoomCode.normalize(decoded)?.let { return it } }
        }

        RoomCode.normalize(trimmed)?.let { return it }

        // Any URL that is not one of ours must not have its path mined for a
        // code: "https://example.com/watchparty/AB34CD56" is not an invitation.
        val searchable = urlPattern.replace(trimmed, " ")

        // Formatted codes embedded in text: "Room code: AB34 CD56".
        splitCodePattern.findAll(searchable).forEach { match ->
            val joined = match.groupValues[1] + match.groupValues[2]
            RoomCode.normalize(joined)?.let { return it }
        }

        // Fall back to the first plausible 8 character token anywhere in the text.
        codePattern.findAll(searchable).forEach { match ->
            RoomCode.normalize(match.groupValues[1])?.let { return it }
        }

        return null
    }

    /** Builds the share text placed in the clipboard. */
    fun inviteText(code: String, episode: EpisodeIdentity? = null): String {
        val link = RoomCode.inviteLink(code)
        val episodeLine = episode?.displayName
        return buildString {
            append("Watch ")
            append(episodeLine ?: "anime")
            append(" together on Hikaru. Room code: ")
            append(RoomCode.format(code))
            append('\n')
            append(link)
        }
    }
}
