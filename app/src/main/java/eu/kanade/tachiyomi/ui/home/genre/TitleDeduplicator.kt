package eu.kanade.tachiyomi.ui.home.genre

import tachiyomi.domain.anime.model.Anime

object TitleDeduplicator {
    private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
    private val SPACES_REGEX = Regex("\\s+")

    private val EDITION_TAG_REGEX = Regex(
        "\\s*(?:\\(|\\[)(?:tv|sub|dub|subbed|dubbed|uncensored|censored|1080p|720p|480p|bd|ova|ona|\\d{4})(?:\\)|\\])\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeTitle(title: String): String {
        return title
            .trim()
            .lowercase()
            .replace(PUNCTUATION_REGEX, "")
            .replace(SPACES_REGEX, " ")
            .trim()
    }

    fun normalizeBaseTitle(title: String): String {
        val stripped = title.replace(EDITION_TAG_REGEX, " ")
        val normalized = normalizeTitle(stripped)
        return normalized.ifBlank { normalizeTitle(title) }
    }

    fun <T> selectBestMatch(
        targetTitle: String,
        candidates: List<T>,
        titleSelector: (T) -> String,
    ): T? {
        if (candidates.isEmpty()) return null
        val targetNorm = normalizeTitle(targetTitle)
        val targetBase = normalizeBaseTitle(targetTitle)
        if (targetNorm.isBlank() && targetBase.isBlank()) return null

        // 1. Exact normalized match
        candidates.firstOrNull { normalizeTitle(titleSelector(it)) == targetNorm }?.let { return it }

        // 2. Edition-insensitive base title match
        if (targetBase.isNotBlank()) {
            candidates.firstOrNull { normalizeBaseTitle(titleSelector(it)) == targetBase }?.let { return it }
        }

        // 3. Tight prefix/superstring match (e.g., "Title (TV)" or "Title: Part 1") when lengths are close
        return candidates.firstOrNull { candidate ->
            val candBase = normalizeBaseTitle(titleSelector(candidate))
            candBase.isNotBlank() &&
                targetBase.isNotBlank() &&
                (candBase.startsWith("$targetBase ") || targetBase.startsWith("$candBase ")) &&
                kotlin.math.abs(candBase.length - targetBase.length) <= 12
        }
    }

    fun dedupeAnime(items: List<Anime>): List<Anime> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Anime>()
        for (item in items) {
            val key = normalizeTitle(item.title)
            val effectiveKey = if (key.isBlank()) item.title.trim().lowercase() else key
            if (seen.add(effectiveKey)) {
                result.add(item)
            }
        }
        return result
    }
}
