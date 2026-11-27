package eu.kanade.tachiyomi.data.telegram

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        val episodeEnd: Int? = null,
        val episodeLabel: String,
        val qualityTag: String?,
        val altKeys: List<String> = emptyList(),
        val isFinalSeason: Boolean = false,
        val matchedToken: String? = null,
        val normalizedName: String = "",
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
        val hasRealPoster: Boolean = false,
        val posterChatId: Long = 0L,
        val posterMessageId: Long = 0L,
        val isFinalSeason: Boolean = false,
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
        val altTitles: List<String> = emptyList(),
        val isFinalSeasonHint: Boolean = false,
    )

    private data class WorkImage(
        val image: Td.ChatImage,
        val parsed: ParsedMedia?,
        val index: Int = 0,
    )

    private data class CachedOptionalParsed(val parsed: ParsedMedia?)

    private data class ExtractionState(
        var text: String,
        var season: Int = 0,
        var episodeNumber: Float? = null,
        var episodeEnd: Int? = null,
        var kind: SeriesKind = SeriesKind.MAIN,
        var kindNumber: Float? = null,
        var isFinalSeason: Boolean = false,
        var matchedToken: String? = null,
        var variantLabel: String? = null,
    )

    private const val PARSE_CACHE_LIMIT = 4096
    private const val MAX_EPISODE_NUM = 1500
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2099
    private const val FINAL_SEASON_SORT = Int.MAX_VALUE - 1
    private val parseVideoCache = ConcurrentHashMap<String, ParsedMedia>()
    private val parseImageCache = ConcurrentHashMap<String, CachedOptionalParsed>()

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
        """(?<![A-Za-z0-9])(2160p|4k|1440p|2k|1080p|720p|480p|360p|240p)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val SCENE_GROUP_TAIL_REGEX = Regex(
        """(2160p|1440p|1080p|720p|480p|360p|x264|x265|h\.?264|h\.?265|""" +
            """hevc|av1|web-?dl|webrip|bluray|aac|flac|opus)\s*-[A-Za-z0-9]{2,15}\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val CRC32_BRACKET_REGEX = Regex("""[\[(][0-9A-Fa-f]{8}[\])]""")
    private val YEAR_BRACKET_REGEX = Regex("""[\[(](?:19\d{2}|20[0-3]\d)[\])]""")
    private val BRACKET_BLOCK_REGEX = Regex("""[\[(【]([^\]\)】]{1,80})[\])】]""")
    private val LEADING_GROUP_KEEP_REGEX = Regex(
        """^(?:\d{1,4}(?:\.\d+)?(?:v\d)?(?:\s*END)?|S\d{1,2}.*|E\d{1,4}.*|""" +
            """Season.*|OVA.*|OAV.*|ONA.*|Specials?.*|Extras?.*|Movie.*|Film.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val WORD_NOISE_REGEX = Regex(
        """\b(?:dual(?:\s+audio)?|multi(?:\s+audio)?|hevc|x264|x265|h\.?264|h\.?265|""" +
            """10\s*bit|8\s*bit|av1|web-?dl|webrip|bluray|bdrip|hdrip|dvdrip|""" +
            """uncensored|censored|ddp?\s*5\.1|aac\s*2\.0|flac|opus|eac3|ac3|esub|hsub|hdr10\+?|sdr)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AUDIO_CHANNEL_REGEX = Regex("""\b(?:5\.1|7\.1|2\.0)\b""")

    // Tier 1: Combined Season + Episode patterns (ranges first, then single)
    private val COMBINED_SE_RANGE_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[\])]?\s*[-._xX:\s]*[\[(]?\s*E(?:p(?:isode)?)?\.?\s*[-_.]?\s*""" +
            """(\d{1,4})\s*[-~〜]\s*(?:S\1\s*[-._xX:\s]*)?(?:E(?:p(?:isode)?)?\.?\s*[-_.]?\s*)?(\d{1,4})(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_X_RANGE_REGEX = Regex(
        """\b(\d{1,2})x(\d{1,4})\s*[-~〜]\s*(?:\1x)?(\d{1,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_WORD_SE_REGEX = Regex(
        """(?:\bSeason\s*(\d{1,2})|\b(\d{1,2})(?:st|nd|rd|th)\s+Season)\s*[-–:\s]*""" +
            """E(?:p(?:isode)?)?\.?\s*[-_.]?\s*(\d{1,4})(?:\s*[-~〜]\s*(\d{1,4}))?(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_SE_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[\])]?\s*[-–._xX:\s]*[\[(]?\s*E(?:p(?:isode)?)?\.?\s*[-_.]?\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_DOT_SE_REGEX = Regex(
        """\bS(\d{1,2})\.(?:E(?:p(?:isode)?)?)?(\d{2,4})(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_S_DASH_EP_REGEX = Regex(
        """[\[(]?\s*S(\d{1,2})\s*[-:]\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_X_REGEX = Regex("""\b(\d{1,2})x(\d{1,4}(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)
    private val COMBINED_TEMP_CAP_REGEX = Regex(
        """\b(?:Temporada|Temp\.?)\s*(\d{1,2})\s*[-–:\s]*Cap(?:itulo|ítulo)?\.?\s*(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_T_EP_REGEX = Regex(
        """(?<![A-Za-z0-9\-])T(\d{1,2})\s*[-–:\s]?\s*(?:C(?:ap)?|E(?:p(?:isode)?)?)\.?\s*(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_STAFFEL_FOLGE_REGEX = Regex(
        """\bStaffel\s*(\d{1,2})\s*[-–:\s]*Folge\s*(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_SAISON_EP_REGEX = Regex(
        """\bSaison\s*(\d{1,2})\s*[-–:\s]*[EÉ]pisode\s*(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINED_CJK_SE_REGEX = Regex(
        """(?:第\s*)?(\d{1,2})\s*期\s*[-–:\s]*(?:第\s*)?(\d{1,4}(?:\.\d+)?)\s*話""",
    )
    private val COMBINED_KR_SE_REGEX = Regex(
        """(?:시즌\s*(\d{1,2})|(\d{1,2})\s*기)\s*[-–:\s]*(\d{1,4}(?:\.\d+)?)\s*화""",
    )
    private val COMBINED_VI_SE_REGEX = Regex(
        """(?<![A-Za-z\p{L}])M[uù]a\s*(\d{1,2})\s*[-–:\s]*T[aậ]p\s*(\d{1,4}(?:\.\d+)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Tier 2: Kind / Specials / Variants
    private val KIND_OVA_REGEX = Regex(
        """\b(?:OAV|OVA)(?:\s*[-_.]?\s*(?:E(?:p(?:isode)?)?\s*)?(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_ONA_REGEX = Regex(
        """\bONA(?:\s*[-_.]?\s*(?:E(?:p(?:isode)?)?\s*)?(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_SPECIAL_REGEX = Regex(
        """\b(?:Specials?|Spacials?)(?:\s*[-_.]?\s*(?:E(?:p(?:isode)?)?\s*)?(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_SP_UPPER_REGEX = Regex("""\bSP(?:\s*[-_.]?\s*(\d{1,3}))?\b""")
    private val KIND_EXTRA_REGEX = Regex(
        """\bExtras?(?:\s*[-_.]?\s*(?:E(?:p(?:isode)?)?\s*)?(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIND_EX_UPPER_REGEX = Regex("""\bEX(?:\s*[-_.]?\s*(\d{1,3}))?\b""")
    private val KIND_MOVIE_REGEX = Regex(
        """[\[(]?\b(?:Movie|Film)\b(?:\s*[-_.]?\s*(\d{1,2}))?[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val VARIANT_EXTRA_REGEX = Regex(
        """\b(NCED|NCOP|PV|CM|Trailer|Bonus|Omake)(?:\s*[-_.]?\s*(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val VARIANT_SPECIAL_REGEX = Regex(
        """\b(Recap|Summary|Prologue|Epilogue)(?:\s*[-_.]?\s*(\d{1,3}))?\b""",
        RegexOption.IGNORE_CASE,
    )

    // Tier 3: Explicit Season Patterns
    private val FINAL_SEASON_REGEX = Regex(
        """\b(?:The\s+Final\s+Season|Final\s+Season|Season\s+Final|Last\s+Season)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_STD_REGEX = Regex(
        """(?:^|(?<=[\s\[(\.\-_]))(?:Season[\s.\-_]*(\d{1,2})|S\s*[-]?\s*(\d{1,2}))(?=$|[\s\])\-_.:])""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_ORDINAL_REGEX = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)\s+(?:Season|Cour)\b|\bCour\s*[-_.\s]*(\d{1,2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_WORD_REGEX = Regex(
        """\b(First|Second|Third|Fourth|Fifth|Sixth|Seventh|Eighth|Ninth|Tenth)\s+Season\b|""" +
            """\bSeason\s+(One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_MULTILINGUAL_REGEX = Regex(
        """\bTemporada\s*[-_.\s]*(\d{1,2})\b|\bTemp\.?\s*[-_.\s]*(\d{1,2})\b|""" +
            """\bStaffel\s*[-_.\s]*(\d{1,2})\b|\bSaison\s*[-_.\s]*(\d{1,2})\b|""" +
            """(?<![A-Za-z0-9\-])T([1-9]|1\d|20)(?![A-Za-z0-9\-])|""" +
            """(?<![A-Za-z\p{L}])M[uù]a\s*[-_.\s]*(\d{1,2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_CJK_KR_REGEX = Regex(
        """シーズン\s*(\d{1,2})|(?:第\s*)?(\d{1,2})\s*期|시즌\s*(\d{1,2})|(\d{1,2})\s*기""",
    )
    private val PART_SEASON_REGEX = Regex(
        """\bPart\s*[-_.\s]*(?:(\d{1,2})|(I{1,3}|IV|VI{0,3}|IX|X))\b""",
        RegexOption.IGNORE_CASE,
    )

    // Tier 4: Explicit Episode Patterns & Ranges
    private val EP_TAG_RANGE_REGEX = Regex(
        """[\[(]?\s*(?:E(?:p(?:isode)?s?)?\.?\s*[-_.]?\s*)(\d{1,4})\s*[-~〜]\s*""" +
            """(?:E(?:p(?:isode)?s?)?\.?\s*[-_.]?\s*)?(\d{1,4})(?:v\d)?\s*[\])]?""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_TILDE_RANGE_REGEX = Regex("""\b(\d{1,4})\s*[~〜]\s*(\d{1,4})\b""")
    private val TRAILING_RANGE_REGEX = Regex(
        """(?:[-–|\s]|\[\s*)(\d{2,4})\s*[-~〜]\s*(\d{2,4})(?:\s*\])?(?:\s*(?:END|v\d))?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val EXPLICIT_EP_WORD_REGEX = Regex(
        """(?:^|(?<=[\s\-_|\[(\.]))(?:Episodes?|Eps?\.?)\s*[-_.]?\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EXPLICIT_E_LETTER_REGEX = Regex(
        """(?:^|(?<=[\s\-_|\[(\.]))E\s*[-_.]?\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EP_MULTILINGUAL_REGEX = Regex(
        """(?:^|(?<=[\s\-_|\[(\.]))(?:Cap(?:itulo|ítulo)?|Folge|[EÉ]pisode|T[aậ]p|Bag(?:ian)?)""" +
            """\.?\s*[-_.]?\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\b|""" +
            """(?:^|(?<=[\s\-_|\[(\.]))(?:#|No\.?\s*)(\d{1,4}(?:\.\d+)?)(?:v\d)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EP_CJK_KR_REGEX = Regex(
        """(?:第\s*)?(\d{1,4}(?:\.\d+)?)\s*話|화\s*(\d{1,4}(?:\.\d+)?)|(\d{1,4}(?:\.\d+)?)\s*화""",
    )
    private val BRACKET_EP_REGEX = Regex(
        """[\[(]\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?(?:\s*END)?\s*[\])]""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_SEP_EP_REGEX = Regex(
        """^\s*(\d{1,4}(?:\.\d+)?)(?:v\d)?\s*[-–:|]\s+(\S.{1,})$""",
    )
    private val TRAILING_SEP_EP_REGEX = Regex(
        """(?:\s+[-–|]\s+)(\d{1,4}(?:\.\d+)?)(?:v\d)?(?:\s+END)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_PADDED_EP_REGEX = Regex(
        """(?<!Part)(?<!Cour)(?<!Vol)(?<!Arc)(?<!Season)\s+(0\d{1,3}(?:\.\d+)?)(?:v\d)?(?:\s+END)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT_AFTER_NUM_REGEX = Regex(
        """^(?:days?|girlfriends?|kanojo|things?|minutes?|seconds?|hours?|years?|percent|meters?|steps?|realms?|kingdoms?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DOT_CLEAN_REGEX = Regex(
        """(?<!\d)\.(?!\d)|\.(?=[A-Za-z])|(?<=[A-Za-z])\.|\.(?=(?:2160|1440|1080|720|480|360|240)p\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val EMPTY_BRACKETS_REGEX = Regex("""\[\s*\]|\(\s*\)|【\s*】""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val EDGE_PUNCT_REGEX = Regex("""^[\s\-–:|_./•✦◆▪▸]+|[\s\-–:|_./•✦◆▪▸]+$""")
    private val NON_ALNUM_UNICODE_REGEX = Regex("""[^\p{L}\p{N}]""")
    private val NON_ALNUM_OR_SPACE_UNICODE_REGEX = Regex("""[^\p{L}\p{N}\s]+""")
    private val APOSTROPHE_REGEX = Regex("""['’]+""")
    private val KEY_VALUE_LINE_REGEX = Regex("""^([A-Za-z][A-Za-z0-9 ]{1,18})\s*[:\-–=]\s*(.+)$""")
    private val PROMO_LINE_REGEX = Regex(
        """^(?:join|follow|subscribe|mirror|backup|channel|link|download|powered\s+by|encoded\s+by|credit|uploader)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR_TOKEN_REGEX = Regex("""\b(?:19|20)\d{2}\b""")
    private val AIRING_SEASON_REGEX = Regex(
        """^\s*(?:spring|summer|fall|autumn|winter)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val SEASON_WORD_MAP = mapOf(
        "one" to 1, "first" to 1,
        "two" to 2, "second" to 2,
        "three" to 3, "third" to 3,
        "four" to 4, "fourth" to 4,
        "five" to 5, "fifth" to 5,
        "six" to 6, "sixth" to 6,
        "seven" to 7, "seventh" to 7,
        "eight" to 8, "eighth" to 8,
        "nine" to 9, "ninth" to 9,
        "ten" to 10, "tenth" to 10,
    )

    private val ROMAN_NUMERAL_MAP = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
    )

    private val TITLE_LINE_KEYS = setOf(
        "name", "title", "anime", "series", "movie", "show",
        "english", "eng", "romaji", "alternative", "alt title", "native",
        "synonym", "synonyms", "aka", "other",
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
        "aac", "flac", "opus", "ac3", "eac3", "ddp", "dd", "20", "51", "71",
        "uncensored", "censored", "raw", "complete", "batch", "end", "fin", "final",
        "v2", "v3", "repack", "proper", "mkv", "mp4", "hdr", "hdr10", "sdr",
        "2160p", "4k", "1440p", "2k", "1080p", "720p", "480p", "360p", "240p",
        "subsplease", "erairaws", "judas", "ember", "akihito", "toonshub",
        "golumpa", "dkb", "asw", "ssa", "yameii", "kai",
    )

    val EPISODE_SORT_COMPARATOR: Comparator<ParsedVideo> = Comparator { a, b ->
        val unknownCmp = (a.parsed.episodeNumber == null).compareTo(b.parsed.episodeNumber == null)
        if (unknownCmp != 0) return@Comparator unknownCmp
        if (a.parsed.episodeNumber != null) {
            val seasonCmp = sortSeasonOf(a.parsed).compareTo(sortSeasonOf(b.parsed))
            if (seasonCmp != 0) return@Comparator seasonCmp
            val epCmp = a.parsed.episodeNumber.compareTo(b.parsed.episodeNumber!!)
            if (epCmp != 0) return@Comparator epCmp
            val qCmp = qualityRank(b).compareTo(qualityRank(a))
            if (qCmp != 0) return@Comparator qCmp
        }
        val dateCmp = a.video.date.compareTo(b.video.date)
        if (dateCmp != 0) return@Comparator dateCmp
        a.video.messageId.compareTo(b.video.messageId)
    }

    private fun sortSeasonOf(p: ParsedMedia): Int = when {
        p.rawSeason > 0 -> p.rawSeason
        p.isFinalSeason -> FINAL_SEASON_SORT
        else -> p.effectiveSeason
    }

    @Suppress("ReturnCount")
    private fun qualityRank(pv: ParsedVideo): Int {
        val h = pv.video.height
        if (h >= 2160) return 5
        if (h >= 1440) return 4
        if (h >= 1080) return 3
        if (h >= 720) return 2
        if (h > 0) return 1
        return when (pv.parsed.qualityTag) {
            "2160p" -> 5
            "1440p" -> 4
            "1080p" -> 3
            "720p" -> 2
            "480p", "360p", "240p" -> 1
            else -> 0
        }
    }

    fun normalizeBaseKey(title: String): String {
        val lower = title.lowercase(Locale.US)
        val stripped = NON_ALNUM_UNICODE_REGEX.replace(lower, "")
        return stripped.ifBlank { lower }
    }

    fun normalizeSeriesName(title: String): String {
        val noExt = EXT_REGEX.replace(title.trim(), "")
        val replaced = APOSTROPHE_REGEX.replace(noExt.lowercase(Locale.US).replace('_', ' '), "")
        return NON_ALNUM_OR_SPACE_UNICODE_REGEX.replace(replaced, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

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

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun extractCaption(caption: String): CaptionExtraction {
        if (caption.isBlank()) return CaptionExtraction("", 0, null, null, null)
        var chosenTitle = ""
        var seasonHint = 0
        var episodeHint: Float? = null
        var kindHint: SeriesKind? = null
        var qualityHint: String? = null
        var finalHint = false
        val altTitles = ArrayList<String>()

        val lines = caption.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(14).toList()
        for (rawLine in lines) {
            var line = URL_REGEX.replace(rawLine, " ")
            line = HANDLE_REGEX.replace(line, " ")
            line = LETTER_HASHTAG_REGEX.replace(line, " ")
            line = MULTI_SPACE_REGEX.replace(line.replace('_', ' '), " ").trim()
            line = stripDecorativeEdges(line)
            if (line.isBlank() || PROMO_LINE_REGEX.containsMatchIn(line)) continue

            if (qualityHint == null) qualityHint = extractQualityTag(line)

            val kv = KEY_VALUE_LINE_REGEX.matchEntire(line)
            if (kv != null) {
                val keyLower = kv.groupValues[1].trim().lowercase(Locale.US)
                val valText = stripDecorativeEdges(kv.groupValues[2])
                if (keyLower in STOP_LINE_KEYS) break
                if (keyLower in TITLE_LINE_KEYS) {
                    if (valText.isNotBlank()) {
                        if (chosenTitle.isBlank()) {
                            chosenTitle = valText
                        } else {
                            val parts = valText.split(Regex("""\s*[,;|/]\s*"""))
                                .map { it.trim() }
                                .filter { it.length >= 2 }
                            altTitles.addAll(parts)
                        }
                    }
                    continue
                }
                if (keyLower in HINT_LINE_KEYS) {
                    if (keyLower == "season" && seasonHint == 0) {
                        if (FINAL_SEASON_REGEX.containsMatchIn(valText)) finalHint = true
                        seasonHint = parseSeasonHintValue(valText)
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
                if (FINAL_SEASON_REGEX.containsMatchIn(line)) finalHint = true
                continue
            }

            if (chosenTitle.isBlank()) {
                chosenTitle = line
            } else if (chosenTitle.length <= 4 && line.length > chosenTitle.length) {
                chosenTitle = line
            }
        }
        return CaptionExtraction(
            titleText = chosenTitle,
            seasonHint = seasonHint,
            episodeHint = episodeHint,
            kindHint = kindHint,
            qualityHint = qualityHint,
            altTitles = altTitles,
            isFinalSeasonHint = finalHint,
        )
    }

    private fun parseSeasonHintValue(valText: String): Int {
        val withoutYears = YEAR_TOKEN_REGEX.replace(valText, " ").trim()
        if (withoutYears.isBlank() || AIRING_SEASON_REGEX.matches(withoutYears)) return 0
        val explicit = detectSeasonOnly(withoutYears)
        if (explicit > 0) return explicit
        return Regex("""^\s*(\d{1,2})\s*$""").matchEntire(withoutYears)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun detectSeasonOnly(text: String): Int {
        SEASON_STD_REGEX.find(text)?.let { m ->
            val v = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            if (v != null && v > 0) return v
        }
        SEASON_ORDINAL_REGEX.find(text)?.let { m ->
            val v = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            if (v != null && v > 0) return v
        }
        SEASON_WORD_REGEX.find(text)?.let { m ->
            val word = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase(Locale.US)
            SEASON_WORD_MAP[word]?.let { return it }
        }
        SEASON_MULTILINGUAL_REGEX.find(text)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        SEASON_CJK_KR_REGEX.find(text)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        return 0
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
        val cacheKey = "${video.chatId}:${video.messageId}:${video.fileId}:" +
            "${video.title.hashCode()}:${video.fileName.hashCode()}:${video.caption.hashCode()}"
        parseVideoCache[cacheKey]?.let { return it }
        val result = parseVideoUncached(video)
        if (parseVideoCache.size >= PARSE_CACHE_LIMIT) parseVideoCache.clear()
        parseVideoCache[cacheKey] = result
        return result
    }

    private fun parseVideoUncached(video: Td.ChatVideo): ParsedMedia {
        val cleanFile = video.fileName.trim()
        val usableFile = cleanFile.isNotBlank() && !PLACEHOLDER_FILE_REGEX.matches(cleanFile)
        val cap = extractCaption(video.caption)

        val pFile = if (usableFile) parse(cleanFile, fallback = "") else null
        val pCap = if (cap.titleText.isNotBlank()) parse(cap.titleText, fallback = "") else null

        val chosen = when {
            pFile != null && !isGenericTitle(pFile.seriesTitle) ->
                mergeParsed(primary = pFile, secondary = pCap, cap = cap)
            pCap != null && !isGenericTitle(pCap.seriesTitle) ->
                mergeParsed(primary = pCap, secondary = pFile, cap = cap)
            pFile != null ->
                mergeParsed(primary = pFile, secondary = pCap, cap = cap)
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
        val epEnd = primary.episodeEnd ?: secondary?.episodeEnd
        val rawSeason = maxOf(primary.rawSeason, secondary?.rawSeason ?: 0, cap.seasonHint)
        val isFinal = primary.isFinalSeason || secondary?.isFinalSeason == true || cap.isFinalSeasonHint
        val kind = when {
            primary.kind != SeriesKind.MAIN -> primary.kind
            secondary != null && secondary.kind != SeriesKind.MAIN -> secondary.kind
            cap.kindHint != null -> cap.kindHint
            else -> SeriesKind.MAIN
        }
        val quality = primary.qualityTag ?: secondary?.qualityTag ?: cap.qualityHint
        val token = primary.matchedToken ?: secondary?.matchedToken
        val effSeason = if (rawSeason <= 1) 1 else rawSeason
        val altKeys = cap.altTitles
            .map { parse(it, fallback = "").baseKey }
            .filter { it.length >= 3 && it != primary.baseKey }
            .distinct()
        return primary.copy(
            rawSeason = rawSeason,
            effectiveSeason = effSeason,
            kind = kind,
            groupKey = primary.baseKey,
            episodeNumber = ep,
            episodeEnd = epEnd,
            episodeLabel = formatEpisodeLabel(ep, epEnd, rawSeason, kind, isFinal, null),
            displayTitle = primary.seriesTitle,
            qualityTag = quality,
            altKeys = (primary.altKeys + altKeys).distinct(),
            isFinalSeason = isFinal,
            matchedToken = token,
        )
    }

    fun parseImage(image: Td.ChatImage): ParsedMedia? {
        val cacheKey = "${image.chatId}:${image.messageId}:${image.fileId}:" +
            "${image.fileName.hashCode()}:${image.caption.hashCode()}"
        parseImageCache[cacheKey]?.let { return it.parsed }
        val result = parseImageUncached(image)
        if (parseImageCache.size >= PARSE_CACHE_LIMIT) parseImageCache.clear()
        parseImageCache[cacheKey] = CachedOptionalParsed(result)
        return result
    }

    @Suppress("ReturnCount")
    private fun parseImageUncached(image: Td.ChatImage): ParsedMedia? {
        val cap = extractCaption(image.caption)
        val raw = when {
            cap.titleText.isNotBlank() -> cap.titleText
            image.fileName.isNotBlank() && !PLACEHOLDER_FILE_REGEX.matches(image.fileName.trim()) -> image.fileName.trim()
            else -> return null
        }
        val parsed = parse(raw, fallback = "")
        if (parsed.baseKey.isBlank()) return null
        val season = maxOf(parsed.rawSeason, cap.seasonHint)
        val isFinal = parsed.isFinalSeason || cap.isFinalSeasonHint
        val kind = if (parsed.kind != SeriesKind.MAIN) parsed.kind else (cap.kindHint ?: SeriesKind.MAIN)
        val effSeason = if (season <= 1) 1 else season
        val altKeys = cap.altTitles
            .map { parse(it, fallback = "").baseKey }
            .filter { it.length >= 3 && it != parsed.baseKey }
            .distinct()
        return parsed.copy(
            rawSeason = season,
            effectiveSeason = effSeason,
            kind = kind,
            groupKey = parsed.baseKey,
            displayTitle = parsed.seriesTitle,
            altKeys = altKeys,
            isFinalSeason = isFinal,
        )
    }

    @Suppress("LongMethod")
    fun parse(
        rawInput: String,
        fallback: String = "Video",
        treatPartAsSeason: Boolean = false,
    ): ParsedMedia {
        var text = stripDecorativeEdges(rawInput.trim())
        text = URL_REGEX.replace(text, " ")
        text = HANDLE_REGEX.replace(text, " ")
        text = LETTER_HASHTAG_REGEX.replace(text, " ")
        text = EXT_REGEX.replace(text, "")
        text = SCENE_GROUP_TAIL_REGEX.replace(text, "$1")
        text = text.replace('_', ' ')
        text = DOT_CLEAN_REGEX.replace(text, " ")
        text = MULTI_SPACE_REGEX.replace(text, " ").trim()

        val qualityTag = extractQualityTag(text)

        text = stripLeadingReleaseGroup(text)
        text = CRC32_BRACKET_REGEX.replace(text, " ")
        text = YEAR_BRACKET_REGEX.replace(text, " ")

        val st = ExtractionState(text = text)
        extractCombinedSeasonEpisode(st)
        extractKindAndVariants(st)
        extractExplicitSeason(st, treatPartAsSeason)
        extractExplicitEpisodeTags(st)

        // Strip noise & quality before evaluating strict contextual bare episode numbers
        st.text = stripTechnicalBrackets(st.text)
        st.text = QUALITY_REGEX.replace(st.text, " ")
        st.text = WORD_NOISE_REGEX.replace(st.text, " ")
        st.text = AUDIO_CHANNEL_REGEX.replace(st.text, " ")
        st.text = EMPTY_BRACKETS_REGEX.replace(st.text, " ")
        st.text = MULTI_SPACE_REGEX.replace(st.text, " ").trim()
        st.text = EDGE_PUNCT_REGEX.replace(st.text, "").trim()

        extractContextualBareEpisodes(st)

        if (st.episodeNumber == null && st.kindNumber != null) {
            st.episodeNumber = st.kindNumber
        }

        st.text = EMPTY_BRACKETS_REGEX.replace(st.text, " ")
        st.text = MULTI_SPACE_REGEX.replace(st.text, " ").trim()
        st.text = EDGE_PUNCT_REGEX.replace(st.text, "").trim()
        st.text = stripDecorativeEdges(st.text)

        val seriesTitle = st.text.ifBlank {
            MULTI_SPACE_REGEX.replace(fallback.replace('_', ' '), " ").trim()
        }
        val baseKey = normalizeBaseKey(seriesTitle)
        val normalizedName = normalizeSeriesName(seriesTitle)
        val effectiveSeason = if (st.season <= 1) 1 else st.season
        val epLabel = formatEpisodeLabel(
            epNum = st.episodeNumber,
            epEnd = st.episodeEnd,
            rawSeason = st.season,
            kind = st.kind,
            isFinalSeason = st.isFinalSeason,
            variantLabel = st.variantLabel,
        )

        return ParsedMedia(
            seriesTitle = seriesTitle,
            displayTitle = seriesTitle,
            baseKey = baseKey,
            rawSeason = st.season,
            effectiveSeason = effectiveSeason,
            kind = st.kind,
            groupKey = baseKey,
            episodeNumber = st.episodeNumber,
            episodeEnd = st.episodeEnd,
            episodeLabel = epLabel,
            qualityTag = qualityTag,
            isFinalSeason = st.isFinalSeason,
            matchedToken = st.matchedToken,
            normalizedName = normalizedName,
        )
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun extractCombinedSeasonEpisode(st: ExtractionState) {
        COMBINED_SE_RANGE_REGEX.find(st.text)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 0
            val epStart = m.groupValues[2].toIntOrNull()
            val epEnd = m.groupValues[3].toIntOrNull()
            if (isValidEpisodeRange(epStart, epEnd)) {
                st.season = s
                st.episodeNumber = epStart?.toFloat()
                st.episodeEnd = epEnd
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }
        COMBINED_X_RANGE_REGEX.find(st.text)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 0
            val epStart = m.groupValues[2].toIntOrNull()
            val epEnd = m.groupValues[3].toIntOrNull()
            if (isValidEpisodeRange(epStart, epEnd)) {
                st.season = s
                st.episodeNumber = epStart?.toFloat()
                st.episodeEnd = epEnd
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }
        COMBINED_WORD_SE_REGEX.find(st.text)?.let { m ->
            val s = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: 0
            val epStart = m.groupValues[3].toIntOrNull()
            val epEnd = m.groupValues[4].toIntOrNull()
            if (epStart != null && epStart <= MAX_EPISODE_NUM && (epEnd == null || epEnd >= epStart)) {
                st.season = s
                st.episodeNumber = epStart.toFloat()
                st.episodeEnd = epEnd
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }
        val singlePatterns = listOf(
            COMBINED_SE_REGEX,
            COMBINED_DOT_SE_REGEX,
            COMBINED_S_DASH_EP_REGEX,
            COMBINED_X_REGEX,
            COMBINED_TEMP_CAP_REGEX,
            COMBINED_T_EP_REGEX,
            COMBINED_STAFFEL_FOLGE_REGEX,
            COMBINED_SAISON_EP_REGEX,
            COMBINED_CJK_SE_REGEX,
            COMBINED_VI_SE_REGEX,
        )
        for (regex in singlePatterns) {
            regex.find(st.text)?.let { m ->
                val s = m.groupValues[1].toIntOrNull() ?: 0
                val ep = m.groupValues[2].toFloatOrNull()
                if (ep != null && ep <= MAX_EPISODE_NUM.toFloat()) {
                    st.season = s
                    st.episodeNumber = ep
                    st.matchedToken = m.value.trim()
                    st.text = st.text.removeRange(m.range)
                    return
                }
            }
        }
        COMBINED_KR_SE_REGEX.find(st.text)?.let { m ->
            val s = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: 0
            val ep = m.groupValues[3].toFloatOrNull()
            if (ep != null && ep <= MAX_EPISODE_NUM.toFloat()) {
                st.season = s
                st.episodeNumber = ep
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun extractKindAndVariants(st: ExtractionState) {
        when {
            KIND_OVA_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_OVA_REGEX.find(st.text)
                st.kind = SeriesKind.OVA
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_OVA_REGEX.replace(st.text, " ")
            }
            KIND_ONA_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_ONA_REGEX.find(st.text)
                st.kind = SeriesKind.ONA
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_ONA_REGEX.replace(st.text, " ")
            }
            KIND_SPECIAL_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_SPECIAL_REGEX.find(st.text)
                st.kind = SeriesKind.SPECIAL
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_SPECIAL_REGEX.replace(st.text, " ")
            }
            KIND_SP_UPPER_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_SP_UPPER_REGEX.find(st.text)
                st.kind = SeriesKind.SPECIAL
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_SP_UPPER_REGEX.replace(st.text, " ")
            }
            VARIANT_SPECIAL_REGEX.containsMatchIn(st.text) -> {
                val m = VARIANT_SPECIAL_REGEX.find(st.text)
                st.kind = SeriesKind.SPECIAL
                st.variantLabel = canonicalVariantLabel(m?.groupValues?.getOrNull(1))
                st.kindNumber = m?.groupValues?.getOrNull(2)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = VARIANT_SPECIAL_REGEX.replace(st.text, " ")
            }
            KIND_EXTRA_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_EXTRA_REGEX.find(st.text)
                st.kind = SeriesKind.EXTRA
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_EXTRA_REGEX.replace(st.text, " ")
            }
            KIND_EX_UPPER_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_EX_UPPER_REGEX.find(st.text)
                st.kind = SeriesKind.EXTRA
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_EX_UPPER_REGEX.replace(st.text, " ")
            }
            VARIANT_EXTRA_REGEX.containsMatchIn(st.text) -> {
                val m = VARIANT_EXTRA_REGEX.find(st.text)
                st.kind = SeriesKind.EXTRA
                st.variantLabel = canonicalVariantLabel(m?.groupValues?.getOrNull(1))
                st.kindNumber = m?.groupValues?.getOrNull(2)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = VARIANT_EXTRA_REGEX.replace(st.text, " ")
            }
            KIND_MOVIE_REGEX.containsMatchIn(st.text) -> {
                val m = KIND_MOVIE_REGEX.find(st.text)
                st.kind = SeriesKind.MOVIE
                st.kindNumber = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (st.matchedToken == null) st.matchedToken = m?.value?.trim()
                st.text = KIND_MOVIE_REGEX.replace(st.text, " ")
            }
        }
    }

    private fun canonicalVariantLabel(raw: String?): String? = when (raw?.uppercase(Locale.US)) {
        "NCED", "NCOP", "PV", "CM" -> raw.uppercase(Locale.US)
        null -> null
        else -> raw.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun extractExplicitSeason(st: ExtractionState, treatPartAsSeason: Boolean) {
        FINAL_SEASON_REGEX.find(st.text)?.let { m ->
            st.isFinalSeason = true
            st.text = st.text.removeRange(m.range)
        }
        if (treatPartAsSeason && st.season == 0) {
            PART_SEASON_REGEX.find(st.text)?.let { m ->
                val num = m.groupValues[1].toIntOrNull()
                    ?: ROMAN_NUMERAL_MAP[m.groupValues[2].uppercase(Locale.US)]
                if (num != null && num > 0) {
                    st.season = num
                    st.text = st.text.removeRange(m.range)
                    return
                }
            }
        }
        if (st.season > 0) return

        val regexes = listOf(SEASON_STD_REGEX, SEASON_ORDINAL_REGEX, SEASON_MULTILINGUAL_REGEX, SEASON_CJK_KR_REGEX)
        for (regex in regexes) {
            regex.find(st.text)?.let { m ->
                val sVal = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                if (sVal != null && sVal > 0) {
                    st.season = sVal
                    st.text = st.text.removeRange(m.range)
                    return
                }
            }
        }
        SEASON_WORD_REGEX.find(st.text)?.let { m ->
            val word = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase(Locale.US)
            val sVal = SEASON_WORD_MAP[word]
            if (sVal != null) {
                st.season = sVal
                st.text = st.text.removeRange(m.range)
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun extractExplicitEpisodeTags(st: ExtractionState) {
        if (st.episodeNumber != null) return

        EP_TAG_RANGE_REGEX.find(st.text)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues[2].toIntOrNull()
            if (isValidEpisodeRange(start, end)) {
                st.episodeNumber = start?.toFloat()
                st.episodeEnd = end
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }

        BARE_TILDE_RANGE_REGEX.find(st.text)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues[2].toIntOrNull()
            if (isValidEpisodeRange(start, end)) {
                st.episodeNumber = start?.toFloat()
                st.episodeEnd = end
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }

        val tagRegexes = listOf(
            EXPLICIT_EP_WORD_REGEX,
            EXPLICIT_E_LETTER_REGEX,
            EP_MULTILINGUAL_REGEX,
            EP_CJK_KR_REGEX,
            BRACKET_EP_REGEX,
        )
        for (regex in tagRegexes) {
            regex.find(st.text)?.let { m ->
                val epVal = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toFloatOrNull()
                if (epVal != null && !isLikelyYear(epVal) && epVal <= MAX_EPISODE_NUM.toFloat()) {
                    st.episodeNumber = epVal
                    st.matchedToken = m.value.trim()
                    st.text = st.text.removeRange(m.range)
                    return
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private fun extractContextualBareEpisodes(st: ExtractionState) {
        if (st.episodeNumber != null) return

        TRAILING_RANGE_REGEX.find(st.text)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues[2].toIntOrNull()
            if (isValidEpisodeRange(start, end)) {
                st.episodeNumber = start?.toFloat()
                st.episodeEnd = end
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }

        LEADING_SEP_EP_REGEX.matchEntire(st.text)?.let { m ->
            val epVal = m.groupValues[1].toFloatOrNull()
            val rest = m.groupValues[2].trim()
            val valid = epVal != null && !isLikelyYear(epVal) && epVal <= MAX_EPISODE_NUM.toFloat() &&
                !UNIT_AFTER_NUM_REGEX.containsMatchIn(rest)
            if (valid) {
                st.episodeNumber = epVal
                st.matchedToken = m.groupValues[1]
                st.text = rest
                return
            }
        }

        TRAILING_SEP_EP_REGEX.find(st.text)?.let { m ->
            val epVal = m.groupValues[1].toFloatOrNull()
            if (epVal != null && !isLikelyYear(epVal) && epVal <= MAX_EPISODE_NUM.toFloat()) {
                st.episodeNumber = epVal
                st.matchedToken = m.value.trim()
                st.text = st.text.removeRange(m.range)
                return
            }
        }

        TRAILING_PADDED_EP_REGEX.find(st.text)?.let { m ->
            val epVal = m.groupValues[1].toFloatOrNull()
            if (epVal != null && epVal <= 999f) {
                st.episodeNumber = epVal
                st.matchedToken = m.groupValues[1]
                st.text = st.text.removeRange(m.range)
            }
        }
    }

    private fun isValidEpisodeRange(start: Int?, end: Int?): Boolean {
        if (start == null || end == null) return false
        if (start < 0 || end < start || end > MAX_EPISODE_NUM) return false
        return !(start in MIN_YEAR..MAX_YEAR || end in MIN_YEAR..MAX_YEAR)
    }

    private fun isLikelyYear(ep: Float): Boolean {
        val asInt = ep.toInt()
        return ep % 1f == 0f && asInt in MIN_YEAR..MAX_YEAR
    }

    private fun stripLeadingReleaseGroup(input: String): String {
        var s = input.trimStart()
        while (s.startsWith("[") || s.startsWith("(") || s.startsWith("【")) {
            val closeChar = when (s.first()) {
                '[' -> ']'
                '(' -> ')'
                else -> '】'
            }
            val closeIdx = s.indexOf(closeChar)
            if (closeIdx <= 0 || closeIdx > 48) break
            val inside = s.substring(1, closeIdx).trim()
            if (LEADING_GROUP_KEEP_REGEX.matches(inside)) break
            s = s.substring(closeIdx + 1).trimStart()
        }
        return s
    }

    private fun stripTechnicalBrackets(input: String): String {
        return BRACKET_BLOCK_REGEX.replace(input) { m ->
            val inside = m.groupValues[1].trim()
            if (isNoiseBracket(inside)) " " else m.value
        }
    }

    @Suppress("ReturnCount")
    private fun isNoiseBracket(inside: String): Boolean {
        if (inside.isBlank()) return true
        val cleaned = inside.lowercase(Locale.US)
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(',', ' ')
            .replace('+', ' ')
            .trim()
        val tokens = cleaned.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        return tokens.all { tok ->
            tok in BRACKET_NOISE_TOKENS ||
                Regex("""^\d{3,4}p$""").matches(tok) ||
                Regex("""^(?:x|h)\d{3}$""").matches(tok) ||
                Regex("""^\d+bit$""").matches(tok) ||
                Regex("""^[0-9a-f]{8}$""").matches(tok)
        }
    }

    private fun formatEpisodeLabel(
        epNum: Float?,
        epEnd: Int?,
        rawSeason: Int,
        kind: SeriesKind,
        isFinalSeason: Boolean,
        variantLabel: String?,
    ): String {
        val startText = epNum?.let { n ->
            if (n % 1f == 0f) n.toInt().toString() else n.toString()
        }
        val numText = when {
            startText != null && epEnd != null -> "$startText-$epEnd"
            else -> startText
        }
        val prefix = when {
            variantLabel != null -> variantLabel
            kind == SeriesKind.MAIN -> when {
                rawSeason >= 2 -> "S${rawSeason} Ep"
                isFinalSeason -> "Final Ep"
                else -> "Episode"
            }
            else -> kind.label
        }
        return if (numText != null) "$prefix $numText" else prefix
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun groupVideosAndPosters(
        videos: List<Td.ChatVideo>,
        images: List<Td.ChatImage>,
    ): List<GroupedSeries> {
        if (videos.isEmpty()) return emptyList()

        // Index titled posters strictly by exact baseKey (plus caption altKeys)
        val exactMainPosters = HashMap<String, WorkImage>()
        val exactSeasonKindPosters = HashMap<String, WorkImage>()
        val uncaptionedImages = ArrayList<Td.ChatImage>()

        images.forEachIndexed { idx, img ->
            val p = parseImage(img)
            if (p != null && p.baseKey.isNotBlank()) {
                val wi = WorkImage(img, p, idx)
                exactSeasonKindPosters.putIfAbsent("${p.baseKey}|${p.kind.name}|${p.effectiveSeason}", wi)
                if (p.kind == SeriesKind.MAIN && p.rawSeason <= 1) {
                    exactMainPosters[p.baseKey] = exactMainPosters[p.baseKey]?.takeIf {
                        it.parsed?.kind == SeriesKind.MAIN && it.parsed.rawSeason <= 1
                    } ?: wi
                } else {
                    exactMainPosters.putIfAbsent(p.baseKey, wi)
                }
                for (alt in p.altKeys) {
                    exactSeasonKindPosters.putIfAbsent("$alt|${p.kind.name}|${p.effectiveSeason}", wi)
                    exactMainPosters.putIfAbsent(alt, wi)
                }
            } else {
                uncaptionedImages += img
            }
        }

        // Strict single-pass grouping by groupKey == baseKey ONLY
        val groupsMap = LinkedHashMap<String, MutableList<ParsedVideo>>()
        for (v in videos) {
            val parsed = parseVideo(v)
            groupsMap.getOrPut(parsed.groupKey) { ArrayList() }.add(ParsedVideo(v, parsed))
        }

        // Chronological video order for O(log V) uncaptioned tie-breaker only
        val chronoEntries = ArrayList<Pair<Long, String>>(videos.size)
        for ((key, list) in groupsMap) {
            for (pv in list) {
                chronoEntries += pv.video.messageId to key
            }
        }
        chronoEntries.sortBy { it.first }
        val adjacentUncaptioned = mapUncaptionedPosters(uncaptionedImages, chronoEntries)

        val result = ArrayList<GroupedSeries>(groupsMap.size)
        for ((key, items) in groupsMap) {
            val sortedEpisodes = items.sortedWith(EPISODE_SORT_COMPARATOR)
            val rep = pickRepresentativeMeta(sortedEpisodes)
            val matchedWorkImg = exactSeasonKindPosters["${rep.baseKey}|${rep.kind.name}|${rep.effectiveSeason}"]
                ?: exactSeasonKindPosters["${rep.baseKey}|${SeriesKind.MAIN.name}|1"]
                ?: exactMainPosters[rep.baseKey]
            val matchedPoster = matchedWorkImg?.image ?: adjacentUncaptioned[key]
            val upgradedTitle = chooseBestSeriesTitle(rep.seriesTitle, matchedWorkImg?.parsed?.seriesTitle)

            val fallbackVideo = sortedEpisodes.firstOrNull { it.video.thumbFileId != 0 }?.video
            val fallbackMini = sortedEpisodes.firstOrNull { !it.video.thumb.isNullOrBlank() }?.video?.thumb
            val latestMsg = sortedEpisodes.maxOf { it.video.messageId }
            val earliestMsg = sortedEpisodes.minOf { it.video.messageId }
            val latestDate = sortedEpisodes.maxOf { it.video.date }

            result += GroupedSeries(
                groupKey = key,
                displayTitle = upgradedTitle,
                seriesTitle = upgradedTitle,
                baseKey = rep.baseKey,
                rawSeason = rep.rawSeason,
                effectiveSeason = rep.effectiveSeason,
                kind = rep.kind,
                videos = sortedEpisodes,
                posterFileId = matchedPoster?.fileId ?: fallbackVideo?.thumbFileId ?: 0,
                posterFallbackThumb = matchedPoster?.minithumb ?: fallbackMini,
                latestMessageId = latestMsg,
                latestDate = latestDate,
                earliestMessageId = earliestMsg,
                aliasKeys = setOf(key),
                hasRealPoster = matchedPoster != null,
                posterChatId = matchedPoster?.chatId ?: fallbackVideo?.chatId ?: 0L,
                posterMessageId = matchedPoster?.messageId ?: fallbackVideo?.messageId ?: 0L,
                isFinalSeason = rep.isFinalSeason,
            )
        }
        return result.sortedByDescending { it.latestMessageId }
    }

    private fun pickRepresentativeMeta(sortedEpisodes: List<ParsedVideo>): ParsedMedia {
        val first = sortedEpisodes.first().parsed
        val hasMain = sortedEpisodes.any { it.parsed.kind == SeriesKind.MAIN }
        val repKind = if (hasMain) SeriesKind.MAIN else first.kind
        val distinctSeasons = sortedEpisodes.asSequence()
            .filter { it.parsed.kind == repKind }
            .map { it.parsed.rawSeason }
            .filter { it > 0 }
            .distinct()
            .toList()
        val repRawSeason = if (distinctSeasons.size == 1) distinctSeasons.first() else 0
        val repEffSeason = if (repRawSeason <= 1) 1 else repRawSeason
        val repFinal = sortedEpisodes.all { it.parsed.isFinalSeason }
        val cleanTitle = sortedEpisodes
            .map { it.parsed.seriesTitle }
            .firstOrNull { !it.endsWith("...") && !it.endsWith("…") }
            ?: first.seriesTitle
        return first.copy(
            seriesTitle = cleanTitle,
            displayTitle = cleanTitle,
            rawSeason = repRawSeason,
            effectiveSeason = repEffSeason,
            kind = repKind,
            isFinalSeason = repFinal,
        )
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

    @Suppress("ReturnCount")
    fun chooseBestSeriesTitle(videoTitle: String, posterTitle: String?): String {
        if (posterTitle.isNullOrBlank()) return videoTitle
        val videoKey = normalizeBaseKey(videoTitle)
        val posterKey = normalizeBaseKey(posterTitle)
        if (videoKey.isBlank() || videoKey != posterKey) return videoTitle
        val videoUpper = videoTitle.count { it.isUpperCase() }
        val posterUpper = posterTitle.count { it.isUpperCase() }
        val posterHasPunct = posterTitle.any { it in ":;!?'" }
        val videoHasPunct = videoTitle.any { it in ":;!?'" }
        return if ((posterHasPunct && !videoHasPunct) || posterUpper > videoUpper) {
            posterTitle
        } else {
            videoTitle
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun formatSeriesDisplayTitle(seriesTitle: String, effectiveSeason: Int, kind: SeriesKind): String = seriesTitle

    @Suppress("ReturnCount")
    fun buildPosterSearchQuery(
        seriesTitle: String,
        rawSeason: Int = 0,
        kind: SeriesKind = SeriesKind.MAIN,
        isFinalSeason: Boolean = false,
    ): String {
        val cleanSource = parse(seriesTitle, fallback = seriesTitle).seriesTitle
        val base = normalizeSeriesName(cleanSource).ifBlank { normalizeSeriesName(seriesTitle) }
        if (base.isBlank()) return ""
        val modifier = when {
            rawSeason > 0 -> String.format(Locale.US, "S%02d", rawSeason)
            kind == SeriesKind.OVA -> "OVA"
            kind == SeriesKind.ONA -> "ONA"
            kind == SeriesKind.EXTRA -> "Extra"
            kind == SeriesKind.SPECIAL -> "Special"
            kind == SeriesKind.MOVIE -> "Movie"
            isFinalSeason -> "Final Season"
            else -> null
        }
        return if (modifier != null) "$base $modifier" else base
    }

    @Suppress("ReturnCount")
    fun buildPosterSearchChunks(
        seriesTitle: String,
        rawSeason: Int = 0,
        kind: SeriesKind = SeriesKind.MAIN,
        isFinalSeason: Boolean = false,
    ): List<String> {
        val primary = buildPosterSearchQuery(seriesTitle, rawSeason, kind, isFinalSeason)
        if (primary.isBlank()) return emptyList()
        val cleanBase = buildPosterSearchQuery(seriesTitle)
        if (cleanBase.length < 2) return emptyList()
        val queries = LinkedHashSet<String>()
        queries += primary
        queries += cleanBase
        return queries.toList()
    }

    @Suppress("UNUSED_PARAMETER", "ReturnCount")
    fun matchesSeriesPoster(
        targetSeriesTitle: String,
        targetGroupKey: String,
        targetKind: SeriesKind,
        targetSeason: Int,
        image: Td.ChatImage,
    ): ParsedMedia? {
        val imgParsed = parseImage(image) ?: return null
        val targetKey = targetGroupKey.ifBlank { normalizeBaseKey(targetSeriesTitle) }
        if (targetKey.length < 2 || imgParsed.baseKey.length < 2) return null
        return if (imgParsed.baseKey == targetKey || targetKey in imgParsed.altKeys) {
            imgParsed
        } else {
            null
        }
    }

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    fun matchesSeriesVideo(
        video: Td.ChatVideo,
        targetGroupKey: String,
        targetSeriesTitle: String,
        targetKind: SeriesKind,
        targetSeason: Int,
        aliasKeys: Set<String> = emptySet(),
    ): ParsedVideo? {
        val parsed = parseVideo(video)
        val targetKey = targetGroupKey.ifBlank { normalizeBaseKey(targetSeriesTitle) }
        val exactMatch = parsed.groupKey == targetKey ||
            parsed.baseKey == targetKey ||
            parsed.groupKey in aliasKeys ||
            parsed.baseKey in aliasKeys
        return if (exactMatch) ParsedVideo(video, parsed) else null
    }

    @Suppress("ReturnCount")
    fun buildSearchQuery(seriesTitle: String): String {
        val clean = normalizeSeriesName(seriesTitle).ifBlank {
            MULTI_SPACE_REGEX.replace(seriesTitle.replace('_', ' '), " ").trim()
        }
        if (clean.length <= 40) return clean
        val words = clean.split(' ').filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty() && sb.length + 1 + w.length > 40) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
        }
        return sb.toString().ifBlank { clean.take(40) }
    }
}
