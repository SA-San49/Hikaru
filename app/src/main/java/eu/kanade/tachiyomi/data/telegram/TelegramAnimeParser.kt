package eu.kanade.tachiyomi.data.telegram

import java.util.Locale
import kotlin.math.min

@Suppress("MagicNumber", "TooManyFunctions", "LargeClass")
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
        val earliestMessageId: Long = 0L,
        val aliasKeys: Set<String> = emptySet(),
    )

    data class ParsedVideo(
        val video: Td.ChatVideo,
        val parsed: ParsedMedia,
    )

    private data class CaptionExtraction(
        val titleText: String,
        val seasonHint: Int,
        val episodeHint: Float?,
        val kindHint: SeriesKind?,
        val qualityHint: String?,
    )

    private data class NumberCandidate(
        val number: Float,
        val remainderRaw: String,
        val remainderParsed: ParsedMedia,
    )

    private data class WorkVideo(
        val video: Td.ChatVideo,
        var parsed: ParsedMedia,
        val leadingCandidate: NumberCandidate?,
        val trailingCandidate: NumberCandidate?,
    )

    private data class WorkImage(
        val image: Td.ChatImage,
        val parsed: ParsedMedia?,
        val tokens: Set<String>,
    )

    private val EXT_REGEX = Regex(
        """\.(mkv|mp4|avi|webm|mov|m4v|ts|flv|wmv|jpe?g|png|webp)$""",
        RegexOption.IGNORE_CASE,
    )
    private val PLACEHOLDER_FILE_REGEX = Regex(
        """^(?:video|vid|img|doc|file|telegram|tg)?_?\d+(?:_\d+)*\.\w+$""",
        RegexOption.IGNORE_CASE,
    )
    private val HANDLE_REGEX = Regex("""@[A-Za-z0-9_]{2,}""")
    private val LETTER_HASHTAG_REGEX = Regex("""#[A-Za-z][A-Za-z0-9_]*""")
    private val URL_REGEX = Regex("""https?://\S+|t\.me/\S+""", RegexOption.IGNORE_CASE)
    private val QUALITY_REGEX = Regex(
        """\b(2160p|4k|1440p|2k|1080p|720p|480p|360p|240p)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CRC32_BRACKET_REGEX = Regex("""[\[(][0-9A-Fa-f]{8}[\])]""")
    private val YEAR_BRACKET_REGEX = Regex("""[\[(](?:19\d{2}|20[0-3]\d)[\])]""")
    private val BRACKET_BLOCK_REGEX = Regex("""[\[(【]([^\]\)】]{1,80})[\])】]""")
    private val LEADING_GROUP_KEEP_REGEX = Regex(
        """^(?:\d{1,3}(?:\.\d+)?(?:v\d)?(?:\s*END)?|S\d{1,2}.*|OVA|OAV|ONA|Specials?|Spacials?|Extras?|Movie|Film)$""",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_NOISE_REGEX = Regex(
        """\b(?:dual(?:\s+audio)?|multi(?:\s+audio)?|hevc|x264|x265|h\.?264|h\.?265|""" +
            """10\s*bit|8\s*bit|av1|web-?dl|webrip|bluray|bdrip|hdrip|dvdrip|""" +
            """uncensored|aac|flac|opus|eac3|ac3|esub|hsub)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_EP_EXPLICIT_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[-._xX]?\s*E(?:p(?:isode)?)?\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_DASH_EP_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[-:]\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val X_SEASON_EP_REGEX = Regex("""\b(\d{1,2})x(\d{1,4}(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)
    private val SEASON_ONLY_REGEX = Regex(
        """(?:\bSeason\s*(\d{1,2})\b|\bS(\d{1,2})\b|\b(\d{1,2})(?:st|nd|rd|th)\s+Season\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val PART_NUMBER_EP_REGEX = Regex(
        """(?:^|[\s\-_|\[(\.])(?:Ep(?:isode)?\.?\s*[-_.]?\s*|Cap\.?\s*|No\.?\s*|#)(\d{1,4}(?:\.\d+)?)(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val E_PREFIX_EP_REGEX = Regex(
        """(?:^|[\s\-_|\[(])E(\d{1,4}(?:\.\d+)?)(?:v\d)?\b""",
    )
    private val BRACKET_EP_REGEX = Regex(
        """[\[(]\s*(\d{1,3}(?:\.\d+)?)(?:v\d)?(?:\s*END)?\s*[\])]""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_SEP_EP_REGEX = Regex(
        """^\s*(\d{1,3}(?:\.\d+)?)(?:v\d)?\s*[-–:|]\s+(\S.{1,})$""",
    )
    private val LEADING_PADDED_EP_REGEX = Regex(
        """^\s*(0\d{1,2}(?:\.\d+)?)(?:v\d)?\s+(\S.{1,})$""",
    )
    private val LEADING_BARE_NUM_REGEX = Regex(
        """^\s*(\d{1,3}(?:\.\d+)?)\s+([A-Za-z"\'(\[].{2,})$""",
    )
    private val KIND_OVA_REGEX = Regex("""\b(?:OAV|OVA)\b""", RegexOption.IGNORE_CASE)
    private val KIND_ONA_REGEX = Regex("""\bONA\b""", RegexOption.IGNORE_CASE)
    private val KIND_SPECIAL_REGEX = Regex(
        """\b(?:Specials?|Spacials?)\b(?:\s*[-_.]?\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_SP_UPPER_REGEX = Regex("""\bSP(?:\s*[-_.]?\s*(\d{1,3}))?\b""")
    private val KIND_EXTRA_REGEX = Regex(
        """\bExtras?\b(?:\s*[-_.]?\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_EX_UPPER_REGEX = Regex("""\bEX(?:\s*[-_.]?\s*(\d{1,3}))?\b""")
    private val KIND_MOVIE_REGEX = Regex("""[\[(]?\b(?:Movie|Film)\b[\])]?""", RegexOption.IGNORE_CASE)
    private val TRAILING_SEP_EP_REGEX = Regex(
        """(?:\s+[-–|]\s+|\s+#)(\d{1,4}(?:\.\d+)?)(?:v\d)?(?:\s+END)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_PADDED_EP_REGEX = Regex(
        """(?<!Part)(?<!Cour)(?<!Vol)(?<!Arc)(?<!Season)\s+(0\d{1,2}(?:\.\d+)?)(?:v\d)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_BARE_NUM_REGEX = Regex(
        """^(.{3,}?)\s+(\d{1,3}(?:\.\d+)?)$""",
    )
    private val STRUCTURAL_BEFORE_NUM_REGEX = Regex(
        """\b(?:Part|Pt|Cour|Vol|Volume|Arc|Book|Act|Season)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT_AFTER_NUM_REGEX = Regex(
        """^(?:days?|girlfriends?|kanojo|things?|minutes?|seconds?|hours?|years?|percent|meters?|steps?|realms?|kingdoms?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DOT_BETWEEN_TEXT_REGEX = Regex("""(?<!\d)\.(?!\d)|\.(?=[A-Za-z])|(?<=[A-Za-z])\.""")
    private val EMPTY_BRACKETS_REGEX = Regex("""\[\s*\]|\(\s*\)|【\s*】""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val EDGE_PUNCT_REGEX = Regex("""^[\s\-–:|_./•✦◆▪▸]+|[\s\-–:|_./•✦◆▪▸]+$""")
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]""")
    private val KEY_VALUE_LINE_REGEX = Regex("""^([A-Za-z][A-Za-z0-9 ]{1,18})\s*[:\-–=]\s*(.+)$""")
    private val PROMO_LINE_REGEX = Regex(
        """^(?:join|follow|subscribe|mirror|backup|channel|link|download|powered\s+by|encoded\s+by|credit|uploader)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val TITLE_LINE_KEYS = setOf(
        "name", "title", "anime", "series", "movie", "show",
        "english", "eng", "romaji", "alternative", "alt title", "native",
    )
    private val HINT_LINE_KEYS = setOf("season", "episode", "episodes", "ep", "type", "kind", "format", "quality", "resolution")
    private val STOP_LINE_KEYS = setOf("synopsis", "plot", "description", "summary", "story", "about")
    private val IGNORE_LINE_KEYS = setOf(
        "rating", "score", "mal", "anilist", "anilist id", "imdb", "genre", "genres",
        "tags", "audio", "language", "lang", "subtitle", "subtitles", "sub", "subs",
        "codec", "size", "duration", "runtime", "status", "studio", "studios", "source",
        "year", "aired", "premiered", "broadcast", "votes", "note", "notes", "info",
    )
    private val BRACKET_NOISE_TOKENS = setOf(
        "dual", "multi", "audio", "sub", "dub", "subs", "dubs", "esub", "hsub",
        "eng", "english", "jap", "jpn", "japanese", "hindi", "hin", "tam", "tamil",
        "tel", "telugu", "kor", "korean", "chi", "chinese", "indo", "ind",
        "hevc", "avc", "x264", "x265", "h264", "h265", "av1", "10bit", "8bit", "bit",
        "web", "dl", "webdl", "webrip", "bluray", "bd", "bdrip", "hdrip", "dvdrip",
        "aac", "flac", "opus", "ac3", "eac3", "ddp", "dd", "20", "51",
        "uncensored", "censored", "raw", "complete", "batch", "end", "fin", "final",
        "v2", "v3", "repack", "proper", "mkv", "mp4", "hdr", "sdr",
        "2160p", "4k", "1440p", "2k", "1080p", "720p", "480p", "360p", "240p",
        "subsplease", "erairaws", "judas", "ember", "akihito", "toonshub",
        "golumpa", "dkb", "asw", "ssa", "yameii", "kai",
    )
    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "of", "in", "to", "and", "for", "is", "with", "on", "no", "wa", "ga", "wo", "ni",
    )
    private val SEQUEL_ONLY_SUFFIXES = setOf(
        "2", "3", "4", "5", "ii", "iii", "iv", "v", "vi",
        "season2", "season3", "season4", "s2", "s3", "s4",
        "part2", "part3", "cour2", "cour3", "ova", "ona", "movie", "special",
    )

    private fun isGenericTitle(title: String): Boolean {
        val lower = title.trim().lowercase(Locale.US)
        if (lower.isBlank()) return true
        if (lower.all { it.isDigit() }) return true
        return lower in setOf("video", "ep", "episode", "part", "file", "movie", "ova", "ona", "special", "extra")
    }

    private fun stripDecorativeEdges(input: String): String {
        var start = 0
        var end = input.length
        while (start < end) {
            val cp = Character.codePointAt(input, start)
            if (!isDecorativeCodePoint(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = Character.codePointBefore(input, end)
            if (!isDecorativeCodePoint(cp)) break
            end -= Character.charCount(cp)
        }
        return input.substring(start, end).trim()
    }

    private fun isDecorativeCodePoint(cp: Int): Boolean {
        if (Character.isWhitespace(cp)) return true
        val type = Character.getType(cp)
        if (type == Character.OTHER_SYMBOL.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return true
        }
        return cp == '|'.code || cp == '•'.code || cp == '‣'.code || cp == '›'.code || cp == '»'.code
    }

    @Suppress("CyclomaticComplexMethod")
    private fun extractCaption(caption: String): CaptionExtraction {
        if (caption.isBlank()) return CaptionExtraction("", 0, null, null, null)
        var chosenTitle = ""
        var seasonHint = 0
        var episodeHint: Float? = null
        var kindHint: SeriesKind? = null
        var qualityHint: String? = null

        val lines = caption.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(12).toList()
        for (rawLine in lines) {
            var line = URL_REGEX.replace(rawLine, " ")
            line = HANDLE_REGEX.replace(line, " ")
            line = LETTER_HASHTAG_REGEX.replace(line, " ")
            line = stripDecorativeEdges(line)
            if (line.isBlank() || PROMO_LINE_REGEX.containsMatchIn(line)) continue

            if (qualityHint == null) {
                qualityHint = extractQualityTag(line)
            }

            val kv = KEY_VALUE_LINE_REGEX.matchEntire(line)
            if (kv != null) {
                val keyLower = kv.groupValues[1].trim().lowercase(Locale.US)
                val valText = stripDecorativeEdges(kv.groupValues[2])
                if (keyLower in STOP_LINE_KEYS) break
                if (keyLower in TITLE_LINE_KEYS) {
                    if (chosenTitle.isBlank() && valText.isNotBlank()) chosenTitle = valText
                    continue
                }
                if (keyLower in HINT_LINE_KEYS) {
                    if (keyLower == "season" && seasonHint == 0) {
                        seasonHint = Regex("""\d{1,2}""").find(valText)?.value?.toIntOrNull() ?: 0
                    } else if ((keyLower == "episode" || keyLower == "ep") && episodeHint == null) {
                        episodeHint = Regex("""\d{1,4}(?:\.\d+)?""").find(valText)?.value?.toFloatOrNull()
                    } else if (kindHint == null) {
                        kindHint = detectKind(valText)
                    }
                    continue
                }
                if (keyLower in IGNORE_LINE_KEYS) continue
            }

            if (isTechnicalOnlyLine(line)) {
                if (kindHint == null) kindHint = detectKind(line)
                if (seasonHint == 0) seasonHint = detectSeasonOnly(line)
                continue
            }

            if (chosenTitle.isBlank()) {
                chosenTitle = line
            } else if (chosenTitle.length <= 4 && line.length > chosenTitle.length) {
                chosenTitle = line
            }
        }
        return CaptionExtraction(chosenTitle, seasonHint, episodeHint, kindHint, qualityHint)
    }

    private fun isTechnicalOnlyLine(line: String): Boolean {
        val clean = line.replace(QUALITY_REGEX, " ").replace(WORD_NOISE_REGEX, " ")
            .replace(Regex("""[\[\]()/|•\-–,:+\s]"""), "")
        return clean.isEmpty()
    }

    private fun detectKind(text: String): SeriesKind? = when {
        KIND_OVA_REGEX.containsMatchIn(text) -> SeriesKind.OVA
        KIND_ONA_REGEX.containsMatchIn(text) -> SeriesKind.ONA
        KIND_SPECIAL_REGEX.containsMatchIn(text) || KIND_SP_UPPER_REGEX.containsMatchIn(text) -> SeriesKind.SPECIAL
        KIND_EXTRA_REGEX.containsMatchIn(text) || KIND_EX_UPPER_REGEX.containsMatchIn(text) -> SeriesKind.EXTRA
        KIND_MOVIE_REGEX.containsMatchIn(text) -> SeriesKind.MOVIE
        else -> null
    }

    private fun detectSeasonOnly(text: String): Int {
        val m = SEASON_ONLY_REGEX.find(text) ?: return 0
        return m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: 0
    }

    private fun extractQualityTag(text: String): String? =
        QUALITY_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { tag ->
            when (tag.lowercase(Locale.US)) {
                "4k" -> "2160p"
                "2k" -> "1440p"
                else -> tag.lowercase(Locale.US)
            }
        }

    fun bestRawName(fileName: String, caption: String, fallback: String): String {
        val cleanFile = fileName.trim()
        val cap = extractCaption(caption)
        val usableFile = cleanFile.isNotBlank() && !PLACEHOLDER_FILE_REGEX.matches(cleanFile)
        return when {
            usableFile -> cleanFile
            cap.titleText.isNotBlank() -> cap.titleText
            cleanFile.isNotBlank() -> cleanFile
            else -> fallback.ifBlank { "Video" }
        }
    }

    fun parseVideo(video: Td.ChatVideo): ParsedMedia {
        val cleanFile = video.fileName.trim()
        val usableFile = cleanFile.isNotBlank() && !PLACEHOLDER_FILE_REGEX.matches(cleanFile)
        val cap = extractCaption(video.caption)

        val pFile = if (usableFile) parse(cleanFile, fallback = "") else null
        val pCap = if (cap.titleText.isNotBlank()) parse(cap.titleText, fallback = "") else null

        val chosen = when {
            pFile != null && !isGenericTitle(pFile.seriesTitle) -> {
                val capLonger = pCap != null &&
                    !isGenericTitle(pCap.seriesTitle) &&
                    pCap.baseKey.length > pFile.baseKey.length + 3
                val preferCaptionTitle = capLonger &&
                    pFile.baseKey.length >= 12 &&
                    pCap!!.baseKey.startsWith(pFile.baseKey)
                if (preferCaptionTitle) {
                    mergeParsed(primary = pCap!!, secondary = pFile, cap = cap)
                } else {
                    mergeParsed(primary = pFile, secondary = pCap, cap = cap)
                }
            }
            pCap != null && !isGenericTitle(pCap.seriesTitle) -> mergeParsed(primary = pCap, secondary = pFile, cap = cap)
            pFile != null -> mergeParsed(primary = pFile, secondary = pCap, cap = cap)
            else -> parse(video.title, fallback = "Video #${video.messageId}")
        }
        return if (chosen.seriesTitle.isBlank()) {
            parse(video.title, fallback = "Video #${video.messageId}")
        } else {
            chosen
        }
    }

    private fun mergeParsed(
        primary: ParsedMedia,
        secondary: ParsedMedia?,
        cap: CaptionExtraction,
    ): ParsedMedia {
        val ep = primary.episodeNumber ?: secondary?.episodeNumber ?: cap.episodeHint
        val rawSeason = maxOf(primary.rawSeason, secondary?.rawSeason ?: 0, cap.seasonHint)
        val kind = when {
            primary.kind != SeriesKind.MAIN -> primary.kind
            secondary != null && secondary.kind != SeriesKind.MAIN -> secondary.kind
            cap.kindHint != null -> cap.kindHint
            else -> SeriesKind.MAIN
        }
        val quality = primary.qualityTag ?: secondary?.qualityTag ?: cap.qualityHint
        val effSeason = if (rawSeason <= 1) 1 else rawSeason
        val groupKey = "${primary.baseKey}|${kind.name}|$effSeason"
        return primary.copy(
            rawSeason = rawSeason,
            effectiveSeason = effSeason,
            kind = kind,
            groupKey = groupKey,
            episodeNumber = ep,
            episodeLabel = formatEpisodeLabel(ep, rawSeason, kind),
            displayTitle = formatDisplayTitle(primary.seriesTitle, effSeason, kind),
            qualityTag = quality,
        )
    }

    fun parseImage(image: Td.ChatImage): ParsedMedia? {
        val cap = extractCaption(image.caption)
        val raw = when {
            cap.titleText.isNotBlank() -> cap.titleText
            image.fileName.isNotBlank() && !PLACEHOLDER_FILE_REGEX.matches(image.fileName.trim()) -> image.fileName.trim()
            else -> return null
        }
        val parsed = parse(raw, fallback = "")
        if (parsed.baseKey.isBlank()) return null
        val season = maxOf(parsed.rawSeason, cap.seasonHint)
        val kind = if (parsed.kind != SeriesKind.MAIN) parsed.kind else (cap.kindHint ?: SeriesKind.MAIN)
        val effSeason = if (season <= 1) 1 else season
        return parsed.copy(
            rawSeason = season,
            effectiveSeason = effSeason,
            kind = kind,
            groupKey = "${parsed.baseKey}|${kind.name}|$effSeason",
            displayTitle = formatDisplayTitle(parsed.seriesTitle, effSeason, kind),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(rawInput: String, fallback: String = "Video"): ParsedMedia {
        var text = stripDecorativeEdges(rawInput.trim())
        text = URL_REGEX.replace(text, " ")
        text = HANDLE_REGEX.replace(text, " ")
        text = LETTER_HASHTAG_REGEX.replace(text, " ")
        text = EXT_REGEX.replace(text, "")
        text = text.replace('_', ' ')
        text = DOT_BETWEEN_TEXT_REGEX.replace(text, " ")

        val qualityTag = extractQualityTag(text)

        text = stripLeadingReleaseGroup(text)
        text = CRC32_BRACKET_REGEX.replace(text, " ")
        text = YEAR_BRACKET_REGEX.replace(text, " ")

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
                kindNumber = KIND_SPECIAL_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                text = KIND_SPECIAL_REGEX.replace(text, " ")
            }
            KIND_SP_UPPER_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.SPECIAL
                kindNumber = KIND_SP_UPPER_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                text = KIND_SP_UPPER_REGEX.replace(text, " ")
            }
            KIND_EXTRA_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.EXTRA
                kindNumber = KIND_EXTRA_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                text = KIND_EXTRA_REGEX.replace(text, " ")
            }
            KIND_EX_UPPER_REGEX.containsMatchIn(text) -> {
                kind = SeriesKind.EXTRA
                kindNumber = KIND_EX_UPPER_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                text = KIND_EX_UPPER_REGEX.replace(text, " ")
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
            E_PREFIX_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null) {
                    episodeNum = epVal
                    text = text.removeRange(m.range)
                }
            }
        }

        if (episodeNum == null) {
            BRACKET_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null && epVal <= 1500f) {
                    episodeNum = epVal
                    text = text.removeRange(m.range)
                }
            }
        }

        text = stripTechnicalBrackets(text)
        text = QUALITY_REGEX.replace(text, " ")
        text = WORD_NOISE_REGEX.replace(text, " ")
        text = EMPTY_BRACKETS_REGEX.replace(text, " ")
        text = MULTI_SPACE_REGEX.replace(text, " ").trim()
        text = EDGE_PUNCT_REGEX.replace(text, "").trim()

        if (episodeNum == null) {
            LEADING_SEP_EP_REGEX.matchEntire(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                val rest = m.groupValues[2].trim()
                if (epVal != null && epVal <= 1500f && !UNIT_AFTER_NUM_REGEX.containsMatchIn(rest)) {
                    episodeNum = epVal
                    text = rest
                }
            }
        }

        if (episodeNum == null) {
            LEADING_PADDED_EP_REGEX.matchEntire(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                val rest = m.groupValues[2].trim()
                if (epVal != null && epVal <= 999f && !UNIT_AFTER_NUM_REGEX.containsMatchIn(rest)) {
                    episodeNum = epVal
                    text = rest
                }
            }
        }

        if (episodeNum == null) {
            TRAILING_SEP_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null && epVal <= 1500f) {
                    episodeNum = epVal
                    text = text.removeRange(m.range)
                }
            }
        }

        if (episodeNum == null) {
            TRAILING_PADDED_EP_REGEX.find(text)?.let { m ->
                val epVal = m.groupValues[1].toFloatOrNull()
                if (epVal != null && epVal <= 999f) {
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
        text = stripDecorativeEdges(text)

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

    private fun stripLeadingReleaseGroup(input: String): String {
        val m = Regex("""^\s*[\[(【]([^\]\)】]{1,35})[\])】]\s*(.+)$""").matchEntire(input) ?: return input
        val inner = m.groupValues[1].trim()
        if (LEADING_GROUP_KEEP_REGEX.matches(inner)) return input
        return m.groupValues[2].trim()
    }

    private fun stripTechnicalBrackets(input: String): String {
        return BRACKET_BLOCK_REGEX.replace(input) { match ->
            val inner = match.groupValues[1].trim()
            if (isTechnicalBracketContent(inner)) " " else match.value
        }
    }

    private fun isTechnicalBracketContent(inner: String): Boolean {
        val rawTokens = inner.lowercase(Locale.US)
            .split(Regex("""[\s\-–_,/+|.:]+"""))
            .filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return true
        return rawTokens.all { tok ->
            tok in BRACKET_NOISE_TOKENS ||
                tok.matches(Regex("""\d+x\d+""")) ||
                tok.matches(Regex("""\d+(?:\.\d+)?(?:mb|gb|fps|hz|kbps)"""))
        }
    }

    private fun extractLeadingCandidate(parsed: ParsedMedia): NumberCandidate? {
        if (parsed.episodeNumber != null) return null
        val m = LEADING_BARE_NUM_REGEX.matchEntire(parsed.seriesTitle) ?: return null
        val num = m.groupValues[1].toFloatOrNull() ?: return null
        if (num <= 0f || num > 1500f) return null
        val rest = m.groupValues[2].trim()
        if (UNIT_AFTER_NUM_REGEX.containsMatchIn(rest)) return null
        val remParsed = parse(rest, fallback = rest)
        if (remParsed.baseKey.length < 3) return null
        val merged = remParsed.copy(
            rawSeason = maxOf(remParsed.rawSeason, parsed.rawSeason),
            kind = if (remParsed.kind != SeriesKind.MAIN) remParsed.kind else parsed.kind,
            episodeNumber = num,
            qualityTag = remParsed.qualityTag ?: parsed.qualityTag,
        )
        val eff = if (merged.rawSeason <= 1) 1 else merged.rawSeason
        val finalRem = merged.copy(
            effectiveSeason = eff,
            groupKey = "${merged.baseKey}|${merged.kind.name}|$eff",
            displayTitle = formatDisplayTitle(merged.seriesTitle, eff, merged.kind),
            episodeLabel = formatEpisodeLabel(num, merged.rawSeason, merged.kind),
        )
        return NumberCandidate(num, rest, finalRem)
    }

    private fun extractTrailingCandidate(parsed: ParsedMedia): NumberCandidate? {
        if (parsed.episodeNumber != null) return null
        val m = TRAILING_BARE_NUM_REGEX.matchEntire(parsed.seriesTitle) ?: return null
        val rest = m.groupValues[1].trim()
        if (STRUCTURAL_BEFORE_NUM_REGEX.containsMatchIn(rest)) return null
        val num = m.groupValues[2].toFloatOrNull() ?: return null
        if (num <= 0f || num > 1500f) return null
        val remParsed = parse(rest, fallback = rest)
        if (remParsed.baseKey.length < 4) return null
        val merged = remParsed.copy(
            rawSeason = maxOf(remParsed.rawSeason, parsed.rawSeason),
            kind = if (remParsed.kind != SeriesKind.MAIN) remParsed.kind else parsed.kind,
            episodeNumber = num,
            qualityTag = remParsed.qualityTag ?: parsed.qualityTag,
        )
        val eff = if (merged.rawSeason <= 1) 1 else merged.rawSeason
        val finalRem = merged.copy(
            effectiveSeason = eff,
            groupKey = "${merged.baseKey}|${merged.kind.name}|$eff",
            displayTitle = formatDisplayTitle(merged.seriesTitle, eff, merged.kind),
            episodeLabel = formatEpisodeLabel(num, merged.rawSeason, merged.kind),
        )
        return NumberCandidate(num, rest, finalRem)
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

    private fun significantTokens(title: String): Set<String> =
        title.lowercase(Locale.US)
            .split(Regex("""[^a-z0-9]+"""))
            .asSequence()
            .filter { it.length >= 2 && it !in TITLE_STOPWORDS }
            .toSet()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun groupVideosAndPosters(
        videos: List<Td.ChatVideo>,
        images: List<Td.ChatImage>,
    ): List<GroupedSeries> {
        if (videos.isEmpty()) return emptyList()

        val parsedImages = images.map { img ->
            val p = parseImage(img)
            val tok = if (p != null) significantTokens(p.seriesTitle) else emptySet()
            WorkImage(img, p, tok)
        }
        val exactGroupPosters = HashMap<String, WorkImage>()
        val baseKindPosters = HashMap<String, WorkImage>()
        val baseMainPosters = HashMap<String, WorkImage>()
        val posterBaseKeys = HashSet<String>()
        val titledImages = ArrayList<WorkImage>()
        val uncaptionedImages = ArrayList<Td.ChatImage>()

        for (wi in parsedImages) {
            val p = wi.parsed
            if (p != null && p.baseKey.isNotBlank()) {
                titledImages += wi
                posterBaseKeys += p.baseKey
                exactGroupPosters.putIfAbsent(p.groupKey, wi)
                if (p.rawSeason == 0) {
                    baseKindPosters.putIfAbsent("${p.baseKey}|${p.kind.name}", wi)
                }
                if (p.kind == SeriesKind.MAIN && p.rawSeason == 0) {
                    baseMainPosters.putIfAbsent(p.baseKey, wi)
                }
            } else {
                uncaptionedImages += wi.image
            }
        }

        // Pass 1: Parse videos & collect leading/trailing bare-number candidates
        val workVideos = ArrayList<WorkVideo>(videos.size)
        val nonNumericBaseKeys = HashSet<String>()
        val leadCounts = HashMap<String, MutableSet<Float>>()
        val trailCounts = HashMap<String, MutableSet<Float>>()

        for (v in videos) {
            val p = parseVideo(v)
            val lead = extractLeadingCandidate(p)
            val trail = if (lead == null) extractTrailingCandidate(p) else null
            if (lead == null && trail == null && p.baseKey.isNotBlank()) {
                nonNumericBaseKeys += p.baseKey
            }
            if (lead != null) {
                leadCounts.getOrPut(lead.remainderParsed.groupKey) { HashSet() }.add(lead.number)
            }
            if (trail != null) {
                trailCounts.getOrPut(trail.remainderParsed.groupKey) { HashSet() }.add(trail.number)
            }
            workVideos += WorkVideo(v, p, lead, trail)
        }

        // Disambiguate leading/trailing bare numbers via sibling count, poster match, or non-numeric siblings
        for (wv in workVideos) {
            val lead = wv.leadingCandidate
            if (lead != null && shouldPromoteCandidate(lead, leadCounts, posterBaseKeys, nonNumericBaseKeys)) {
                wv.parsed = lead.remainderParsed
                continue
            }
            val trail = wv.trailingCandidate
            if (trail != null && shouldPromoteCandidate(trail, trailCounts, posterBaseKeys, nonNumericBaseKeys)) {
                wv.parsed = trail.remainderParsed
            }
        }

        // Pass 2: Group by exact groupKey, then consolidate truncated variants
        val initialGroups = LinkedHashMap<String, MutableList<ParsedVideo>>()
        for (wv in workVideos) {
            initialGroups.getOrPut(wv.parsed.groupKey) { ArrayList() }.add(ParsedVideo(wv.video, wv.parsed))
        }

        val consolidated = consolidateTruncatedGroups(initialGroups)

        // Build chronologically sorted video message index list for fast O(log V) uncaptioned poster proximity
        val chronoEntries = ArrayList<Pair<Long, String>>(workVideos.size)
        val keyRemap = HashMap<String, String>()
        for (cg in consolidated) {
            for (ak in cg.aliasKeys) keyRemap[ak] = cg.canonicalKey
            for (pv in cg.videos) {
                chronoEntries += pv.video.messageId to cg.canonicalKey
            }
        }
        chronoEntries.sortBy { it.first }
        val adjacentUncaptioned = mapUncaptionedPosters(uncaptionedImages, chronoEntries)

        // Pass 3: Match posters across all tiers and produce final GroupedSeries
        val result = ArrayList<GroupedSeries>(consolidated.size)
        for (cg in consolidated) {
            val sample = cg.videos.first().parsed
            val matchedWorkImg = exactGroupPosters[cg.canonicalKey]
                ?: baseKindPosters["${sample.baseKey}|${sample.kind.name}"]
                ?: (if (sample.kind == SeriesKind.MAIN) baseMainPosters[sample.baseKey] else null)
                ?: findBestTitledPoster(sample, cg.tokens, titledImages)

            val matchedPoster = matchedWorkImg?.image ?: adjacentUncaptioned[cg.canonicalKey]
            val upgradedTitle = chooseBestSeriesTitle(sample.seriesTitle, matchedWorkImg?.parsed?.seriesTitle)
            val upgradedDisplay = formatDisplayTitle(upgradedTitle, sample.effectiveSeason, sample.kind)

            val fallbackVideo = cg.videos.firstOrNull { it.video.thumbFileId != 0 }?.video
            val fallbackMini = cg.videos.firstOrNull { !it.video.thumb.isNullOrBlank() }?.video?.thumb
            val latestMsg = cg.videos.maxOf { it.video.messageId }
            val earliestMsg = cg.videos.minOf { it.video.messageId }
            val latestDate = cg.videos.maxOf { it.video.date }

            val sortedEpisodes = cg.videos.sortedWith(
                compareBy<ParsedVideo>(
                    { it.parsed.episodeNumber ?: Float.MAX_VALUE },
                    { it.video.messageId },
                ),
            )

            result += GroupedSeries(
                groupKey = cg.canonicalKey,
                displayTitle = upgradedDisplay,
                seriesTitle = upgradedTitle,
                baseKey = sample.baseKey,
                rawSeason = cg.videos.maxOf { it.parsed.rawSeason },
                effectiveSeason = sample.effectiveSeason,
                kind = sample.kind,
                videos = sortedEpisodes,
                posterFileId = matchedPoster?.fileId ?: fallbackVideo?.thumbFileId ?: 0,
                posterFallbackThumb = matchedPoster?.minithumb ?: fallbackMini,
                latestMessageId = latestMsg,
                latestDate = latestDate,
                earliestMessageId = earliestMsg,
                aliasKeys = cg.aliasKeys,
            )
        }

        return result.sortedByDescending { it.latestMessageId }
    }

    private fun shouldPromoteCandidate(
        cand: NumberCandidate,
        siblingCounts: Map<String, Set<Float>>,
        posterBaseKeys: Set<String>,
        nonNumericBaseKeys: Set<String>,
    ): Boolean {
        val remKey = cand.remainderParsed.baseKey
        if ((siblingCounts[cand.remainderParsed.groupKey]?.size ?: 0) >= 2) return true
        if (remKey in posterBaseKeys || remKey in nonNumericBaseKeys) return true
        if (remKey.length >= 14) {
            if (posterBaseKeys.any { it.startsWith(remKey) || remKey.startsWith(it) }) return true
            if (nonNumericBaseKeys.any { it.startsWith(remKey) || remKey.startsWith(it) }) return true
        }
        return false
    }

    private data class ConsolidatedGroup(
        val canonicalKey: String,
        val videos: MutableList<ParsedVideo>,
        val aliasKeys: MutableSet<String>,
        val tokens: Set<String>,
    )

    private fun consolidateTruncatedGroups(
        initial: Map<String, MutableList<ParsedVideo>>,
    ): List<ConsolidatedGroup> {
        val sorted = initial.entries.sortedByDescending { it.value.first().parsed.baseKey.length }
        val groups = ArrayList<ConsolidatedGroup>(sorted.size)
        for ((key, items) in sorted) {
            val parsed = items.first().parsed
            val bKey = parsed.baseKey
            val target = groups.firstOrNull { existing ->
                val exParsed = existing.videos.first().parsed
                exParsed.kind == parsed.kind &&
                    exParsed.effectiveSeason == parsed.effectiveSeason &&
                    isTruncatedVariant(bKey, parsed.seriesTitle, exParsed.baseKey)
            }
            if (target != null) {
                target.videos.addAll(items)
                target.aliasKeys.add(key)
            } else {
                groups += ConsolidatedGroup(
                    canonicalKey = key,
                    videos = items.toMutableList(),
                    aliasKeys = hashSetOf(key),
                    tokens = significantTokens(parsed.seriesTitle),
                )
            }
        }
        return groups
    }

    private fun isTruncatedVariant(shorterKey: String, shorterTitle: String, longerKey: String): Boolean {
        if (shorterKey == longerKey) return true
        val looksTruncated = shorterKey.length >= 24 || shorterTitle.endsWith("...") || shorterTitle.endsWith("…")
        if (!looksTruncated || shorterKey.length < 12) return false
        if (!longerKey.startsWith(shorterKey)) return false
        val suffix = longerKey.removePrefix(shorterKey)
        return suffix.isNotEmpty() && suffix !in SEQUEL_ONLY_SUFFIXES
    }

    @Suppress("ReturnCount")
    private fun findBestTitledPoster(
        target: ParsedMedia,
        targetTokens: Set<String>,
        images: List<WorkImage>,
    ): WorkImage? {
        if (target.baseKey.length < 4) return null
        var bestPrefix: WorkImage? = null
        var bestOverlap: WorkImage? = null
        var bestScore = 0.0

        for (wi in images) {
            val imgParsed = wi.parsed ?: continue
            if (imgParsed.baseKey.length < 4) continue
            if (imgParsed.kind != target.kind) continue
            if (imgParsed.rawSeason != 0 && imgParsed.effectiveSeason != target.effectiveSeason) continue

            val minLen = min(target.baseKey.length, imgParsed.baseKey.length)
            val isPrefixMatch = target.baseKey.startsWith(imgParsed.baseKey) ||
                imgParsed.baseKey.startsWith(target.baseKey)
            if (minLen >= 10 && isPrefixMatch) {
                val longer = if (target.baseKey.length >= imgParsed.baseKey.length) target.baseKey else imgParsed.baseKey
                val shorter = if (target.baseKey.length < imgParsed.baseKey.length) target.baseKey else imgParsed.baseKey
                val suffix = longer.removePrefix(shorter)
                if (suffix !in SEQUEL_ONLY_SUFFIXES) {
                    if (bestPrefix == null) bestPrefix = wi
                    continue
                }
            }

            if (targetTokens.size >= 3 && wi.tokens.size >= 3) {
                var shared = 0
                for (tok in targetTokens) {
                    if (tok in wi.tokens) shared++
                }
                if (shared >= 3) {
                    val maxContainment = shared.toDouble() / min(targetTokens.size, wi.tokens.size).toDouble()
                    val jaccard = shared.toDouble() / (targetTokens.size + wi.tokens.size - shared).toDouble()
                    val score = if (maxContainment >= 0.9 && jaccard >= 0.65) maxContainment else jaccard
                    if (score >= 0.78 && score > bestScore) {
                        bestScore = score
                        bestOverlap = wi
                    }
                }
            }
        }
        return bestPrefix ?: bestOverlap
    }

    private fun mapUncaptionedPosters(
        uncaptionedImages: List<Td.ChatImage>,
        chronoVideos: List<Pair<Long, String>>,
    ): Map<String, Td.ChatImage> {
        if (uncaptionedImages.isEmpty() || chronoVideos.isEmpty()) return emptyMap()
        val result = HashMap<String, Td.ChatImage>()
        val maxMsgGap = 6L shl 20
        val sortedImgs = uncaptionedImages.sortedBy { it.messageId }
        for (img in sortedImgs) {
            val nextIdx = lowerBoundVideo(chronoVideos, img.messageId)
            if (nextIdx >= chronoVideos.size) continue
            val (nextMsgId, nextKey) = chronoVideos[nextIdx]
            if (nextMsgId - img.messageId > maxMsgGap) continue
            val prevKey = if (nextIdx > 0) chronoVideos[nextIdx - 1].second else null
            if (prevKey != nextKey) {
                result.putIfAbsent(nextKey, img)
            }
        }
        return result
    }

    private fun lowerBoundVideo(entries: List<Pair<Long, String>>, targetId: Long): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].first <= targetId) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun chooseBestSeriesTitle(videoTitle: String, posterTitle: String?): String {
        if (posterTitle.isNullOrBlank()) return videoTitle
        val cleanVideoKey = NON_ALNUM_REGEX.replace(videoTitle.lowercase(Locale.US), "")
        val cleanPosterKey = NON_ALNUM_REGEX.replace(posterTitle.lowercase(Locale.US), "")
        if (cleanPosterKey.length > cleanVideoKey.length && cleanPosterKey.startsWith(cleanVideoKey)) {
            return posterTitle
        }
        return videoTitle
    }

    @Suppress("LongParameterList", "ReturnCount")
    fun matchesSeriesVideo(
        video: Td.ChatVideo,
        targetGroupKey: String,
        targetSeriesTitle: String,
        targetKind: SeriesKind,
        targetSeason: Int,
        aliasKeys: Set<String> = emptySet(),
    ): ParsedVideo? {
        var parsed = parseVideo(video)
        if (parsed.groupKey == targetGroupKey || parsed.groupKey in aliasKeys) {
            return ParsedVideo(video, parsed)
        }
        val lead = extractLeadingCandidate(parsed)
        if (lead != null) parsed = lead.remainderParsed
        if (parsed.groupKey == targetGroupKey || parsed.groupKey in aliasKeys) {
            return ParsedVideo(video, parsed)
        }
        val trail = extractTrailingCandidate(parsed)
        if (trail != null && lead == null) {
            val tp = trail.remainderParsed
            if (tp.groupKey == targetGroupKey || tp.groupKey in aliasKeys) {
                return ParsedVideo(video, tp)
            }
        }

        val targetBase = NON_ALNUM_REGEX.replace(targetSeriesTitle.lowercase(Locale.US), "")
        val seasonOk = parsed.effectiveSeason == targetSeason || (parsed.rawSeason == 0 && targetSeason <= 1)
        val longEnough = targetBase.length >= 10 && parsed.baseKey.length >= 10
        if (parsed.kind == targetKind && seasonOk && longEnough) {
            val prefixHit = targetBase.startsWith(parsed.baseKey) || parsed.baseKey.startsWith(targetBase)
            if (prefixHit) {
                val longer = if (targetBase.length >= parsed.baseKey.length) targetBase else parsed.baseKey
                val shorter = if (targetBase.length < parsed.baseKey.length) targetBase else parsed.baseKey
                if (longer.removePrefix(shorter) !in SEQUEL_ONLY_SUFFIXES) {
                    return ParsedVideo(video, parsed)
                }
            }
        }
        return null
    }

    fun buildSearchQuery(seriesTitle: String): String {
        val clean = seriesTitle.trim().replace(Regex("""[.…]+$"""), "").trim()
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size <= 4 && clean.length <= 36) return clean
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty() && sb.length + 1 + w.length > 36) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
        }
        return sb.toString().ifBlank { clean.take(36) }
    }
}
