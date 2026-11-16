package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * Provider-independent episode matching.
 *
 * The room only shares metadata; each participant resolves their own stream.
 * Matching therefore happens on tracking ids first (AniList / MyAnimeList),
 * then on title + episode number, and finally degrades to a clear "resolve it
 * yourself" state instead of disconnecting anyone.
 */
object EpisodeMatcher {

    private const val EPSILON = 0.01

    /** Title similarity threshold above which two titles are considered the same show. */
    const val TITLE_MATCH_THRESHOLD = 0.86

    /**
     * Compares the room episode with [local] (what this device managed to
     * resolve). [localMediaKey] is the tracking id of the anime currently open in
     * this player, if any.
     */
    fun match(
        room: EpisodeIdentity?,
        local: EpisodeIdentity?,
        localMediaKey: String? = null,
        localMediaKeySource: String? = null,
    ): EpisodeMatch {
        if (room == null || local == null) return EpisodeMatch.UNRESOLVED

        val roomKey = room.mediaKey?.trim()?.takeIf { it.isNotEmpty() }
        val deviceKey = (local.mediaKey ?: localMediaKey)?.trim()?.takeIf { it.isNotEmpty() }
        val deviceSource = local.mediaKeySource ?: localMediaKeySource
        val sameKeySource = room.mediaKeySource == null ||
            deviceSource == null ||
            room.mediaKeySource.equals(deviceSource, ignoreCase = true)

        if (roomKey != null && deviceKey != null && sameKeySource) {
            if (roomKey != deviceKey) return EpisodeMatch.MISMATCH
            return episodeNumberMatch(room.episodeNumber, local.episodeNumber)
        }

        // No shared tracking id: fall back to titles.
        val titlesMatch = titlesSimilar(room.animeTitle, local.animeTitle)
        if (!titlesMatch) {
            // A missing local title is "unknown", not a proven mismatch.
            return if (local.animeTitle.isBlank()) EpisodeMatch.UNRESOLVED else EpisodeMatch.MISMATCH
        }

        return when (episodeNumberMatch(room.episodeNumber, local.episodeNumber)) {
            EpisodeMatch.EXACT -> EpisodeMatch.EXACT
            EpisodeMatch.PROBABLE -> EpisodeMatch.PROBABLE
            else -> EpisodeMatch.MISMATCH
        }
    }

    private fun episodeNumberMatch(room: Double, local: Double): EpisodeMatch = when {
        room < 0 || local < 0 -> EpisodeMatch.PROBABLE
        kotlin.math.abs(room - local) <= EPSILON -> EpisodeMatch.EXACT
        else -> EpisodeMatch.MISMATCH
    }

    /**
     * Fuzzy title comparison that tolerates common provider differences:
     * punctuation, `Season 2` suffixes, `(TV)`, roman numerals vs digits.
     */
    fun titlesSimilar(first: String, second: String): Boolean {
        val a = normalizeTitle(first)
        val b = normalizeTitle(second)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) {
            val shortest = minOf(a.length, b.length)
            // Guard against very short substrings ("K" matching every title).
            if (shortest >= 4) return true
        }
        return similarity(a, b) >= TITLE_MATCH_THRESHOLD
    }

    internal fun normalizeTitle(raw: String): String {
        var value = raw.lowercase()
        // Drop parenthesised qualifiers: "(TV)", "[Sub]", "(2023)".
        value = value.replace(Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]"""), " ")
        // Normalise "season 2" / "2nd season" ordering.
        value = value.replace(Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b"""), " season $1 ")
        // Unify roman numerals used for seasons.
        val roman = mapOf(
            "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
            "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10",
        )
        value = value.split(Regex("""\s+""")).joinToString(" ") { token ->
            roman[token] ?: token
        }
        // Strip everything that is not a letter or digit.
        value = value.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        return value.trim().replace(Regex("""\s+"""), " ")
    }

    /** Normalised Levenshtein similarity in `0..1`. */
    internal fun similarity(first: String, second: String): Double {
        if (first == second) return 1.0
        val longest = maxOf(first.length, second.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(first, second).toDouble() / longest
    }

    private fun levenshtein(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (i in 1..first.length) {
            current[0] = i
            for (j in 1..second.length) {
                val substitutionCost = if (first[i - 1] == second[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }
}
