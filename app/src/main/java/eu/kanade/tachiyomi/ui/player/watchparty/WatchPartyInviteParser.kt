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

    private val codePattern = Regex("""\b([0-9A-Za-z]{8})\b""")

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

        // Fall back to the first plausible 8 character token anywhere in the text.
        codePattern.findAll(trimmed).forEach { match ->
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
