package eu.kanade.tachiyomi.data.telegram

import java.util.Locale

@Suppress("MagicNumber", "TooManyFunctions")
object TelegramAnimeParser {

    enum class SeriesKind(val label: String) {
        MAIN(""),
        OVA("OVA"),
        ONA("ONA"),
        SPECIAL("Special"),
        EXTRA("Extra"),
        MOVIE("Movie"),
    }

    data class ParsedMedia(
        val seriesTitle: String,
        val displayTitle: String,
        val baseKey: String,
        val rawSeason: Int,
        val effectiveSeason: Int,
        val kind: SeriesKind,
        val groupKey: String,
        val episodeNumber: Float?,
        val episodeLabel: String,
        val qualityTag: String?,
    )

    data class GroupedSeries(
        val groupKey: String,
        val displayTitle: String,
        val seriesTitle: String,
        val baseKey: String,
        val rawSeason: Int,
        val effectiveSeason: Int,
        val kind: SeriesKind,
        val videos: List<ParsedVideo>,
        val posterFileId: Int,
        val posterFallbackThumb: String?,
        val latestMessageId: Long,
        val latestDate: Int,
    )

    data class ParsedVideo(
        val video: Td.ChatVideo,
        val parsed: ParsedMedia,
    )

    private val EXT_REGEX = Regex(
        """\.(mkv|mp4|avi|webm|mov|m4v|ts|flv|wmv|jpe?g|png|webp)$""",
        RegexOption.IGNORE_CASE,
    )
    private val HANDLE_REGEX = Regex("""@[A-Za-z0-9_]{2,}""")
    private val URL_REGEX = Regex("""https?://\S+|t\.me/\S+""", RegexOption.IGNORE_CASE)
    private val QUALITY_REGEX = Regex("""\b(2160p|4k|1440p|2k|1080p|720p|480p|360p|240p)\b""", RegexOption.IGNORE_CASE)
    private val BRACKET_NOISE_REGEX = Regex(
        """[\[(]\s*(?:dual(?:\s*audio)?|multi(?:\s*audio)?|sub|dub|eng|jap|jpn|hindi|""" +
            """hevc|x264|x265|h\.?264|h\.?265|10\s*bit|8\s*bit|av1|web-?dl|webrip|""" +
            """bluray|bdrip|hdrip|dvdrip|aac|flac|opus|uncensored|raw|complete|batch|""" +
            """2160p|4k|1440p|2k|1080p|720p|480p|360p|240p|""" +
            """subsplease|erai-?raws|judas|ember|akihito|toonshub|xanime[a-z0-9_]*)\s*[\])]""",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_NOISE_REGEX = Regex(
        """\b(?:dual(?:\s+audio)?|multi(?:\s+audio)?|hevc|x264|x265|h\.?264|h\.?265|""" +
            """10\s*bit|8\s*bit|av1|web-?dl|webrip|bluray|bdrip|hdrip|dvdrip|""" +
            """uncensored|aac|flac|opus)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_EP_EXPLICIT_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[-._xX]?\s*E(?:p(?:isode)?)?\s*(\d{1,4}(?:\.\d+)?)\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_DASH_EP_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[-:]\s*(\d{1,4}(?:\.\d+)?)\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val X_SEASON_EP_REGEX = Regex("""\b(\d{1,2})x(\d{1,4}(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)
    private val SEASON_ONLY_REGEX = Regex(
        """(?:\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b|\b(\d{1,2})(?:st|nd|rd|th)\s+Season\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val PART_NUMBER_EP_REGEX = Regex(
        """(?:^|[\s\-_|\[(\.])(?:Ep(?:isode)?\.?\s*[-_.]?\s*|E\s*[-_.]?\s*)(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_OVA_REGEX = Regex("""\b(?:OAV|OVA)\b""", RegexOption.IGNORE_CASE)
    private val KIND_ONA_REGEX = Regex("""\bONA\b""", RegexOption.IGNORE_CASE)
    private val KIND_SPECIAL_REGEX = Regex(
        """\b(?:Specials?|Spacials?|SP)\b(?:\s*[-_.]?\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_EXTRA_REGEX = Regex(
        """\b(?:Extras?|EX)\b(?:\s*[-_.]?\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_MOVIE_REGEX = Regex("""[\[(]?\b(?:Movie|Film)\b[\])]?""", RegexOption.IGNORE_CASE)
    private val TRAILING_EP_REGEX = Regex("""(?:\s+[-–|]\s+|\s+#)(\d{1,4}(?:\.\d+)?)\s*$""")
    private val DOT_BETWEEN_TEXT_REGEX = Regex("""(?<!\d)\.(?!\d)|\.(?=[A-Za-z])|(?<=[A-Za-z])\.""")
    private val EMPTY_BRACKETS_REGEX = Regex("""\[\s*\]|\(\s*\)""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val EDGE_PUNCT_REGEX = Regex("""^[\s\-–:|_./]+|[\s\-–:|_./]+$""")
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]""")
    private val PROMO_LINE_REGEX = Regex(
        """^(?:join|follow|subscribe|mirror|backup|channel|link|download|powered\s+by)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun bestRawName(fileName: String, caption: String, fallback: String): String {
        val cleanFile = fileName.trim()
        val captionText = caption.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && !PROMO_LINE_REGEX.containsMatchIn(line) }
            .take(3)
            .joinToString(" ")
        return when {
            cleanFile.isNotBlank() && !cleanFile.matches(Regex("""^video_?\d*\.\w+$""", RegexOption.IGNORE_CASE)) ->
                cleanFile
            captionText.isNotBlank() -> captionText
            cleanFile.isNotBlank() -> cleanFile
            else -> fallback.ifBlank { "Video" }
        }
    }

    fun parseVideo(video: Td.ChatVideo): ParsedMedia {
        val raw = bestRawName(video.fileName, video.caption, video.title)
        return parse(raw, fallback = "Video #${video.messageId}")
    }

    fun parseImage(image: Td.ChatImage): ParsedMedia? {
        val raw = bestRawName(image.fileName, image.caption, "")
        if (raw.isBlank()) return null
        return parse(raw, fallback = "")
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(rawInput: String, fallback: String = "Video"): ParsedMedia {
        var text = rawInput.trim()
        text = URL_REGEX.replace(text, " ")
        text = HANDLE_REGEX.replace(text, " ")
        text = EXT_REGEX.replace(text, "")
        text = text.replace('_', ' ')
        text = DOT_BETWEEN_TEXT_REGEX.replace(text, " ")

        val qualityTag = QUALITY_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { tag ->
            when (tag.lowercase(Locale.US)) {
                "4k" -> "2160p"
                "2k" -> "1440p"
                else -> tag.lowercase(Locale.US)
            }
        }

        text = BRACKET_NOISE_REGEX.replace(text, " ")
        text = QUALITY_REGEX.replace(text, " ")
        text = WORD_NOISE_REGEX.replace(text, " ")

        var season = 0
        var episodeNum: Float? = null

        SEASON_EP_EXPLICIT_REGEX.find(text)?.let { m ->
            season = m.groupValues[1].toIntOrNull() ?: 0
            episodeNum = m.groupValues[2].toFloatOrNull()
            text = text.removeRange(m.range)
        }

        if (episodeNum == null) {
            SEASON_DASH_EP_REGEX.find(text)?.let { m ->
                season = m.groupValues[1].toIntOrNull() ?: 0
                episodeNum = m.groupValues[2].toFloatOrNull()
                text = text.removeRange(m.range)
            }
        }

        if (episodeNum == null) {
            X_SEASON_EP_REGEX.find(text)?.let { m ->
                season = m.groupValues[1].toIntOrNull() ?: 0
                episodeNum = m.groupValues[2].toFloatOrNull()
                text = text.removeRange(m.range)
            }
        }

        var kind = SeriesKind.MAIN
        var kindNumber: Float? = null
        when {
            KIND_OVA_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.OVA
                text = KIND_OVA_REGEX.replace(text, " ")
            }
            KIND_ONA_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.ONA
                text = KIND_ONA_REGEX.replace(text, " ")
            }
            KIND_SPECIAL_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.SPECIAL
                KIND_SPECIAL_REGEX.find(text)?.let { m ->
                    kindNumber = m.groupValues.getOrNull(1)?.toFloatOrNull()
                }
                text = KIND_SPECIAL_REGEX.replace(text, " ")
            }
            KIND_EXTRA_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.EXTRA
                KIND_EXTRA_REGEX.find(text)?.let { m ->
                    kindNumber = m.groupValues.getOrNull(1)?.toFloatOrNull()
                }
                text = KIND_EXTRA_REGEX.replace(text, " ")
            }
            KIND_MOVIE_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.MOVIE
                text = KIND_MOVIE_REGEX.replace(text, " ")
            }
        }

        if (season == 0) {
            SEASON_ONLY_REGEX.find(text)?.let { m ->
                val sVal = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                if (sVal != null && sVal > 0) {
                    season = sVal
                    text = text.removeRange(m.range)
                }
            }
        }

        if (episodeNum == null) {
            PART_NUMBER_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null) {
                    episodeNum = epVal
                    text = text.removeRange(m.range)
                }
            }
        }

        if (episodeNum == null) {
            TRAILING_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null && epVal <= 2000f) {
                    episodeNum = epVal
                    text = text.removeRange(m.range)
                }
            }
        }

        if (episodeNum == null && kindNumber != null) {
            episodeNum = kindNumber
        }

        text = EMPTY_BRACKETS_REGEX.replace(text, " ")
        text = MULTI_SPACE_REGEX.replace(text, " ").trim()
        text = EDGE_PUNCT_REGEX.replace(text, "").trim()

        val seriesTitle = text.ifBlank { fallback.trim() }
        val baseKey = NON_ALNUM_REGEX.replace(seriesTitle.lowercase(Locale.US), "")
            .ifBlank { seriesTitle.lowercase(Locale.US) }
        val effectiveSeason = if (season <= 1) 1 else season
        val groupKey = "$baseKey|${kind.name}|$effectiveSeason"
        val displayTitle = formatDisplayTitle(seriesTitle, effectiveSeason, kind)
        val epLabel = formatEpisodeLabel(episodeNum, season, kind)

        return ParsedMedia(
            seriesTitle = seriesTitle,
            displayTitle = displayTitle,
            baseKey = baseKey,
            rawSeason = season,
            effectiveSeason = effectiveSeason,
            kind = kind,
            groupKey = groupKey,
            episodeNumber = episodeNum,
            episodeLabel = epLabel,
            qualityTag = qualityTag,
        )
    }

    private fun formatDisplayTitle(seriesTitle: String, effectiveSeason: Int, kind: SeriesKind): String {
        if (seriesTitle.isBlank()) return ""
        val suffixes = buildList {
            if (effectiveSeason >= 2) add("Season $effectiveSeason")
            if (kind != SeriesKind.MAIN) add(kind.label)
        }
        return if (suffixes.isEmpty()) {
            seriesTitle
        } else {
            "$seriesTitle (${suffixes.joinToString(" ")})"
        }
    }

    private fun formatEpisodeLabel(epNum: Float?, season: Int, kind: SeriesKind): String {
        val numText = epNum?.let { n ->
            if (n % 1f == 0f) n.toInt().toString() else n.toString()
        }
        val prefix = when (kind) {
            SeriesKind.MAIN -> if (season >= 2) "S${season} Ep" else "Episode"
            SeriesKind.OVA -> "OVA"
            SeriesKind.ONA -> "ONA"
            SeriesKind.SPECIAL -> "Special"
            SeriesKind.EXTRA -> "Extra"
            SeriesKind.MOVIE -> "Movie"
        }
        return if (numText != null) "$prefix $numText" else prefix
    }

    @Suppress("CyclomaticComplexMethod")
    fun groupVideosAndPosters(
        videos: List<Td.ChatVideo>,
        images: List<Td.ChatImage>,
    ): List<GroupedSeries> {
        if (videos.isEmpty()) return emptyList()

        val parsedImages = images.mapNotNull { img ->
            val p = parseImage(img) ?: return@mapNotNull null
            img to p
        }
        val exactGroupPosters = HashMap<String, Td.ChatImage>()
        val baseKindPosters = HashMap<String, Td.ChatImage>()
        val baseMainPosters = HashMap<String, Td.ChatImage>()
        for ((img, parsed) in parsedImages) {
            if (parsed.baseKey.isBlank()) continue
            exactGroupPosters.putIfAbsent(parsed.groupKey, img)
            if (parsed.rawSeason == 0) {
                baseKindPosters.putIfAbsent("${parsed.baseKey}|${parsed.kind.name}", img)
            }
            if (parsed.kind == SeriesKind.MAIN && parsed.rawSeason == 0) {
                baseMainPosters.putIfAbsent(parsed.baseKey, img)
            }
        }

        val grouped = LinkedHashMap<String, MutableList<ParsedVideo>>()
        for (v in videos) {
            val parsed = parseVideo(v)
            grouped.getOrPut(parsed.groupKey) { ArrayList() }.add(ParsedVideo(v, parsed))
        }

        val result = ArrayList<GroupedSeries>(grouped.size)
        for ((groupKey, items) in grouped) {
            val firstParsed = items.first().parsed
            val matchedPoster = exactGroupPosters[groupKey]
                ?: baseKindPosters["${firstParsed.baseKey}|${firstParsed.kind.name}"]
                ?: (if (firstParsed.kind == SeriesKind.MAIN) baseMainPosters[firstParsed.baseKey] else null)
                ?: findFuzzyPoster(firstParsed, parsedImages)

            val fallbackVideoWithThumb = items.firstOrNull { it.video.thumbFileId != 0 }?.video
            val fallbackMinithumb = items.firstOrNull { !it.video.thumb.isNullOrBlank() }?.video?.thumb
            val latestMsg = items.maxOf { it.video.messageId }
            val latestDate = items.maxOf { it.video.date }

            val sortedEpisodes = items.sortedWith(
                compareBy<ParsedVideo>(
                    { it.parsed.episodeNumber ?: Float.MAX_VALUE },
                    { it.video.messageId },
                ),
            )

            result += GroupedSeries(
                groupKey = groupKey,
                displayTitle = firstParsed.displayTitle,
                seriesTitle = firstParsed.seriesTitle,
                baseKey = firstParsed.baseKey,
                rawSeason = firstParsed.rawSeason,
                effectiveSeason = firstParsed.effectiveSeason,
                kind = firstParsed.kind,
                videos = sortedEpisodes,
                posterFileId = matchedPoster?.fileId ?: fallbackVideoWithThumb?.thumbFileId ?: 0,
                posterFallbackThumb = matchedPoster?.minithumb ?: fallbackMinithumb,
                latestMessageId = latestMsg,
                latestDate = latestDate,
            )
        }

        return result.sortedByDescending { it.latestMessageId }
    }

    @Suppress("ReturnCount")
    private fun findFuzzyPoster(
        target: ParsedMedia,
        images: List<Pair<Td.ChatImage, ParsedMedia>>,
    ): Td.ChatImage? {
        if (target.baseKey.length < 4) return null
        for ((img, imgParsed) in images) {
            if (imgParsed.baseKey.length < 4) continue
            if (imgParsed.kind != target.kind) continue
            if (imgParsed.rawSeason != 0 && imgParsed.effectiveSeason != target.effectiveSeason) continue
            if (target.baseKey.startsWith(imgParsed.baseKey) || imgParsed.baseKey.startsWith(target.baseKey)) {
                return img
            }
        }
        return null
    }
}
