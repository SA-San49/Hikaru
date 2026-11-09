package eu.kanade.tachiyomi.data.ai

import java.util.Locale

internal enum class AssistantContextIntent {
    DIAGNOSTICS,
    LIBRARY,
    HISTORY,
    BROWSE,
    SCHEDULE,
    FEED,
    COMMUNITY,
    EXTENSION_STATUS,
}

internal object AssistantIntentClassifier {
    private val nonAlphanumericPattern = Regex("[^\\p{L}\\p{N}]+")

    private val diagnosticKeywords = listOf(
        "log", "error", "fail", "video", "loading", "device", "black screen", "broke", "froze", "slow",
        "crash", "bug", "stuck", "lag", "hang", "freeze", "diagnostic",
    )
    private val libraryKeywords = listOf(
        "library", "collection", "my anime", "anime list", "titles i have", "recommend", "recommendation",
    )
    private val historyKeywords = listOf(
        "history", "recently watched", "last watched", "watching history", "watch history", "episodes watched",
        "continue watching", "finished watching",
    )
    private val browseKeywords = listOf(
        "browse", "catalogue", "catalog", "available sources", "visible sources", "source list", "extensions",
    )
    private val scheduleKeywords = listOf(
        "airing", "schedule", "broadcast", "upcoming episode", "next episode", "releasing this week", "coming out",
    )
    private val feedKeywords = listOf(
        "feed", "saved search", "saved feed", "subscriptions", "latest updates", "my updates",
    )
    private val communityKeywords = listOf(
        "everythingmoe", "mirror", "site health", "site status", "stream link", "working extension",
        "broken extension", "1080p", "dub", "sub", "quality",
    )
    private val extensionStatusKeywords = listOf(
        "extension status", "source status", "installed extension",
    )

    fun classify(query: String): Set<AssistantContextIntent> {
        val paddedQuery = " ${normalize(query)} "
        fun matches(keywords: List<String>): Boolean = keywords.any { keyword ->
            val normalized = normalize(keyword)
            val finalSpace = normalized.lastIndexOf(' ')
            val prefix = normalized.substring(0, finalSpace + 1)
            val lastWord = normalized.substring(finalSpace + 1)
            val pluralLastWord = when {
                lastWord.endsWith('y') -> "${lastWord.dropLast(1)}ies"
                lastWord.endsWith("s") || lastWord.endsWith("x") || lastWord.endsWith("z") ||
                    lastWord.endsWith("ch") || lastWord.endsWith("sh") -> "${lastWord}es"
                else -> "${lastWord}s"
            }
            paddedQuery.contains(" $normalized ") || paddedQuery.contains(" $prefix$pluralLastWord ")
        }

        val diagnostics = matches(diagnosticKeywords)
        return buildSet {
            if (diagnostics) add(AssistantContextIntent.DIAGNOSTICS)
            if (matches(libraryKeywords)) add(AssistantContextIntent.LIBRARY)
            if (matches(historyKeywords)) add(AssistantContextIntent.HISTORY)
            if (matches(browseKeywords)) add(AssistantContextIntent.BROWSE)
            if (matches(scheduleKeywords)) add(AssistantContextIntent.SCHEDULE)
            if (matches(feedKeywords)) add(AssistantContextIntent.FEED)
            if (matches(communityKeywords) || matches(listOf("video", "black screen"))) {
                add(AssistantContextIntent.COMMUNITY)
            }
            if (diagnostics || matches(extensionStatusKeywords)) add(AssistantContextIntent.EXTENSION_STATUS)
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(nonAlphanumericPattern, " ")
        .trim()
}
