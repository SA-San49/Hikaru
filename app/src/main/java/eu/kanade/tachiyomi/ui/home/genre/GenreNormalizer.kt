package eu.kanade.tachiyomi.ui.home.genre

object GenreNormalizer {
    private val ALIASES = mapOf(
        "actions" to "action",
        "adventures" to "adventure",
        "comedies" to "comedy",
        "dramas" to "drama",
        "fantasies" to "fantasy",
        "horrors" to "horror",
        "mysteries" to "mystery",
        "romances" to "romance",
        "sci-fi" to "sci-fi",
        "scifi" to "sci-fi",
        "science fiction" to "sci-fi",
        "slice of life" to "slice of life",
        "supernaturals" to "supernatural",
        "thrillers" to "thriller",
        "psychologicals" to "psychological",
        "martial arts" to "martial arts",
        "historical" to "historical",
        "historicals" to "historical",
        "shounen" to "shounen",
        "shoujo" to "shoujo",
        "seinen" to "seinen",
        "josei" to "josei",
        "isekai" to "isekai",
        "mecha" to "mecha",
        "sports" to "sports",
    )

    private val ACRONYMS = setOf(
        "sci-fi",
        "tv",
        "ova",
        "ona",
        "dvd",
        "bd",
        "cctv",
    )

    fun toKey(rawName: String): String {
        val collapsed = rawName.trim().replace(Regex("\\s+"), " ").lowercase()
        return ALIASES[collapsed] ?: collapsed
    }

    fun toDisplayName(rawName: String, key: String = toKey(rawName)): String {
        return when (key) {
            "sci-fi" -> "Sci-Fi"
            "slice of life" -> "Slice of Life"
            "martial arts" -> "Martial Arts"
            "tv" -> "TV"
            "ova" -> "OVA"
            "ona" -> "ONA"
            else -> {
                key.split(" ").joinToString(" ") { word ->
                    if (word in ACRONYMS) {
                        word.uppercase()
                    } else {
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
            }
        }
    }
}
