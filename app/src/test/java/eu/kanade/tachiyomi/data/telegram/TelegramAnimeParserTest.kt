package eu.kanade.tachiyomi.data.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("MagicNumber")
class TelegramAnimeParserTest {

    private fun vid(
        msgId: Long,
        fileName: String,
        caption: String = "",
        date: Int = 1000,
        height: Int = 0,
    ): Td.ChatVideo = Td.ChatVideo(
        chatId = -1001L,
        messageId = msgId,
        fileId = msgId.toInt(),
        title = caption.ifBlank { fileName },
        duration = 1400,
        date = date,
        size = 500_000_000L,
        thumb = null,
        caption = caption,
        fileName = fileName,
        height = height,
    )

    private fun img(
        msgId: Long,
        caption: String = "",
        fileName: String = "",
    ): Td.ChatImage = Td.ChatImage(
        chatId = -1001L,
        messageId = msgId,
        fileId = msgId.toInt(),
        fileName = fileName,
        caption = caption,
    )

    @Test
    fun parsesCombinedSeasonEpisodePatterns() {
        val cases = listOf(
            "Show Name S01E02.mkv" to Pair(1, 2f),
            "Show Name S02EP05.mkv" to Pair(2, 5f),
            "Show Name 1x02.mkv" to Pair(1, 2f),
            "Show Name S01 E02.mkv" to Pair(1, 2f),
            "Show_Name_S01_E02.mkv" to Pair(1, 2f),
            "Show Name S01-E02.mkv" to Pair(1, 2f),
            "[S01][E02] Show Name.mkv" to Pair(1, 2f),
            "Show Name S01 - E02.mkv" to Pair(1, 2f),
            "Show Name S01 - Episode 02.mkv" to Pair(1, 2f),
            "Show.Name.S1.E2.1080p.mkv" to Pair(1, 2f),
            "Show.Name.S1.02.1080p.mkv" to Pair(1, 2f),
            "Show Name Season 1 Episode 2.mkv" to Pair(1, 2f),
            "Show Name 1st Season Episode 2.mkv" to Pair(1, 2f),
            "Show Name 2nd Season E01.mkv" to Pair(2, 1f),
            "Show Name Temporada 1 Capitulo 2.mkv" to Pair(1, 2f),
            "Show Name T1C02.mkv" to Pair(1, 2f),
            "Show Name T1 E02.mkv" to Pair(1, 2f),
            "Show Name Staffel 1 Folge 2.mkv" to Pair(1, 2f),
            "Show Name Saison 1 Épisode 2.mkv" to Pair(1, 2f),
            "Show Name 第1期 第02話.mkv" to Pair(1, 2f),
            "Show Name 1期 02話.mkv" to Pair(1, 2f),
            "Show Name 시즌1 2화.mkv" to Pair(1, 2f),
        )
        for ((input, expected) in cases) {
            val parsed = TelegramAnimeParser.parse(input)
            assertEquals("showname", parsed.baseKey, "baseKey failed for: $input")
            assertEquals(expected.first, parsed.effectiveSeason, "season failed for: $input")
            assertEquals(expected.second, parsed.episodeNumber, "episode failed for: $input")
        }
    }

    @Test
    fun parsesEpisodeRangesCleanly() {
        val rangeCases = listOf(
            "Show Name S01E01-E03.mkv" to Triple(1, 1f, 3),
            "Show Name S01E01-03.mkv" to Triple(1, 1f, 3),
            "Show Name S02E10-E12.mkv" to Triple(2, 10f, 12),
            "Show Name S01EP01-EP02.mkv" to Triple(1, 1f, 2),
            "Show Name 1x01-1x03.mkv" to Triple(1, 1f, 3),
            "Show Name 1x01-03.mkv" to Triple(1, 1f, 3),
            "Show Name E01-E02.mkv" to Triple(1, 1f, 2),
            "Show Name E01-02.mkv" to Triple(1, 1f, 2),
            "Show Name EP01-03.mkv" to Triple(1, 1f, 3),
            "Show Name Episodes 1-3.mkv" to Triple(1, 1f, 3),
            "Show Name Episode 01-02.mkv" to Triple(1, 1f, 2),
            "Show Name 01-02.mkv" to Triple(1, 1f, 2),
            "Show Name 01~02.mkv" to Triple(1, 1f, 2),
            "Show Name 01〜02.mkv" to Triple(1, 1f, 2),
        )
        for ((input, expected) in rangeCases) {
            val parsed = TelegramAnimeParser.parse(input)
            assertEquals("showname", parsed.baseKey, "baseKey failed for: $input")
            assertEquals(expected.first, parsed.effectiveSeason, "season failed for: $input")
            assertEquals(expected.second, parsed.episodeNumber, "epStart failed for: $input")
            assertEquals(expected.third, parsed.episodeEnd, "epEnd failed for: $input")
        }
    }

    @Test
    fun parsesExplicitAndMultilingualEpisodes() {
        val epCases = listOf(
            "Show Name E01.mkv" to 1f,
            "Show Name E1.mkv" to 1f,
            "Show Name E 01.mkv" to 1f,
            "Show Name E-01.mkv" to 1f,
            "Show Name EP01.mkv" to 1f,
            "Show Name Ep.01.mkv" to 1f,
            "Show Name Episode 12.mp4" to 12f,
            "Show Name Episode.12.mp4" to 12f,
            "Show Name Eps.05.mp4" to 5f,
            "Show Name Cap 03.mkv" to 3f,
            "Show Name Capitulo 04.mkv" to 4f,
            "Show Name Capítulo 12.mkv" to 12f,
            "Show Name Folge 07.mkv" to 7f,
            "Show Name Épisode 09.mkv" to 9f,
            "Show Name 第08話.mkv" to 8f,
            "Show Name 8話.mkv" to 8f,
            "Show Name 화01.mkv" to 1f,
            "Show Name 1화.mkv" to 1f,
            "Show Name Tập 06.mkv" to 6f,
            "Show Name Tap 06.mkv" to 6f,
            "Show Name Bagian 04.mkv" to 4f,
            "Show Name Bag 04.mkv" to 4f,
            "[Group] Show Name - 01 [1080p].mkv" to 1f,
            "Show Name - 001 [720p].mkv" to 1f,
            "Show Name 0001.mkv" to 1f,
        )
        for ((input, expectedEp) in epCases) {
            val parsed = TelegramAnimeParser.parse(input)
            assertEquals("showname", parsed.baseKey, "baseKey failed for: $input")
            assertEquals(1, parsed.effectiveSeason, "default season failed for: $input")
            assertEquals(expectedEp, parsed.episodeNumber, "episode failed for: $input")
        }
    }

    @Test
    fun parsesSpecialsVariantsAndKinds() {
        assertEquals(TelegramAnimeParser.SeriesKind.OVA, TelegramAnimeParser.parse("Show Name OVA01.mkv").kind)
        assertEquals(1f, TelegramAnimeParser.parse("Show Name OVA 1.mkv").episodeNumber)
        assertEquals(1f, TelegramAnimeParser.parse("Show Name Ova E01.mkv").episodeNumber)
        assertEquals(TelegramAnimeParser.SeriesKind.ONA, TelegramAnimeParser.parse("Show Name ONA01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name Special 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name SP01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name NCED01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name NCOP01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name PV01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name CM01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name Trailer 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name Recap 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name Summary 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name Prologue.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.SPECIAL, TelegramAnimeParser.parse("Show Name Epilogue.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name Extra 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name Bonus 01.mkv").kind)
        assertEquals(TelegramAnimeParser.SeriesKind.EXTRA, TelegramAnimeParser.parse("Show Name Omake 01.mkv").kind)
    }

    @Test
    fun normalizesSeasonsAndSupportsOptionalPartFlag() {
        assertEquals(1, TelegramAnimeParser.parse("Show Name S01 E01.mkv").effectiveSeason)
        assertEquals(1, TelegramAnimeParser.parse("Show Name S1 E01.mkv").effectiveSeason)
        assertEquals(1, TelegramAnimeParser.parse("Show Name Season 1 Episode 1.mkv").effectiveSeason)
        assertEquals(1, TelegramAnimeParser.parse("Show Name 1st Season Ep 1.mkv").effectiveSeason)
        assertEquals(2, TelegramAnimeParser.parse("Show Name Second Season Ep 1.mkv").effectiveSeason)
        assertEquals(3, TelegramAnimeParser.parse("Show Name Season Three Ep 1.mkv").effectiveSeason)
        assertEquals(2, TelegramAnimeParser.parse("Show Name シーズン2 05話.mkv").effectiveSeason)
        assertEquals(2, TelegramAnimeParser.parse("Show Name Mùa 2 Tập 01.mkv").effectiveSeason)
        assertEquals(2, TelegramAnimeParser.parse("Show Name 2기 01화.mkv").effectiveSeason)

        val partDefault = TelegramAnimeParser.parse("Show Name Part 2 E01.mkv", treatPartAsSeason = false)
        assertEquals(1, partDefault.effectiveSeason)
        assertEquals("shownamepart2", partDefault.baseKey)

        val partAsSeason = TelegramAnimeParser.parse("Show Name Part II E01.mkv", treatPartAsSeason = true)
        assertEquals(2, partAsSeason.effectiveSeason)
        assertEquals("showname", partAsSeason.baseKey)
    }

    @Test
    fun preservesNumericTitlesAndDoesNotInventEpisodes() {
        val gf = TelegramAnimeParser.parse("100 Girlfriends E01.mkv")
        assertEquals("100girlfriends", gf.baseKey)
        assertEquals(1f, gf.episodeNumber)

        val mob = TelegramAnimeParser.parse("Mob Psycho 100 S02E03.mkv")
        assertEquals("mobpsycho100", mob.baseKey)
        assertEquals(2, mob.effectiveSeason)
        assertEquals(3f, mob.episodeNumber)

        val sequelTitle = TelegramAnimeParser.parse("Show Name 2.mkv")
        assertEquals("showname2", sequelTitle.baseKey)
        assertNull(sequelTitle.episodeNumber)
        assertEquals(1, sequelTitle.effectiveSeason)
    }

    @Test
    fun groupsStrictlyByNormalizedTitleWithoutCrossMerging() {
        val videos = listOf(
            vid(1, "Show Name - S02E03.mkv", height = 720),
            vid(2, "Show Name E01.mkv"),
            vid(3, "Show Name S01E01 [1080p].mkv", height = 1080),
            vid(4, "Show Name OVA 1.mkv"),
            vid(5, "Show Name The Final Season E02.mkv"),
            vid(6, "Show Name Unknown Video.mkv", date = 2000),
            vid(7, "Show Name Zero - E01.mkv"),
            vid(8, "Other Show 1 [1080p].mkv"),
            vid(9, "Other Show 2 [1080p].mkv"),
        )
        val images = listOf(
            img(10, caption = "Show Name Zero"),
            img(11, caption = "Title: Show Name"),
        )

        val groups = TelegramAnimeParser.groupVideosAndPosters(videos, images)
        val byKey = groups.associateBy { it.groupKey }

        assertEquals(setOf("showname", "shownamezero", "othershow1", "othershow2"), byKey.keys)

        val showGroup = byKey.getValue("showname")
        assertEquals(6, showGroup.videos.size)
        assertEquals(11L, showGroup.posterMessageId)

        // Order inside group: season asc, episode asc, higher quality first on duplicates, unknown last
        val orderIds = showGroup.videos.map { it.video.messageId }
        assertEquals(listOf(3L, 2L, 4L, 1L, 5L, 6L), orderIds)

        val zeroGroup = byKey.getValue("shownamezero")
        assertEquals(1, zeroGroup.videos.size)
        assertEquals(10L, zeroGroup.posterMessageId)
    }

    @Test
    fun enforcesPosterQueryRulesAndExactMatching() {
        assertEquals(
            "show name S02",
            TelegramAnimeParser.buildPosterSearchQuery(
                seriesTitle = "Show Name",
                rawSeason = 2,
                kind = TelegramAnimeParser.SeriesKind.OVA,
            ),
        )
        assertEquals(
            "show name OVA",
            TelegramAnimeParser.buildPosterSearchQuery(
                seriesTitle = "Show Name",
                rawSeason = 0,
                kind = TelegramAnimeParser.SeriesKind.OVA,
            ),
        )
        assertEquals(
            "show name ONA",
            TelegramAnimeParser.buildPosterSearchQuery(
                seriesTitle = "Show Name",
                rawSeason = 0,
                kind = TelegramAnimeParser.SeriesKind.ONA,
            ),
        )
        assertEquals(
            "show name Extra",
            TelegramAnimeParser.buildPosterSearchQuery(
                seriesTitle = "Show Name",
                rawSeason = 0,
                kind = TelegramAnimeParser.SeriesKind.EXTRA,
            ),
        )
        assertEquals(
            "show name Special",
            TelegramAnimeParser.buildPosterSearchQuery(
                seriesTitle = "Show Name",
                rawSeason = 0,
                kind = TelegramAnimeParser.SeriesKind.SPECIAL,
            ),
        )
        val plainOnly = TelegramAnimeParser.buildPosterSearchChunks(
            seriesTitle = "[SubsPlease] Show_Name [1080p]",
            rawSeason = 0,
            kind = TelegramAnimeParser.SeriesKind.MAIN,
        )
        val parsedPlain = TelegramAnimeParser.parse("[SubsPlease] Show_Name E05 [1080p].mkv")
        assertEquals("show name", parsedPlain.normalizedName)
        assertEquals(
            "show name",
            TelegramAnimeParser.buildPosterSearchQuery(parsedPlain.seriesTitle, parsedPlain.rawSeason, parsedPlain.kind),
        )
        assertFalse(plainOnly.any { it.contains("1080p") || it.contains("SubsPlease") || it.contains("E05") })

        val matchingImg = img(100, caption = "Show Name S02")
        val differentImg = img(101, caption = "Show Name Zero")
        assertNotNull(
            TelegramAnimeParser.matchesSeriesPoster(
                targetSeriesTitle = "Show Name",
                targetGroupKey = "showname",
                targetKind = TelegramAnimeParser.SeriesKind.MAIN,
                targetSeason = 2,
                image = matchingImg,
            ),
        )
        assertNull(
            TelegramAnimeParser.matchesSeriesPoster(
                targetSeriesTitle = "Show Name",
                targetGroupKey = "showname",
                targetKind = TelegramAnimeParser.SeriesKind.MAIN,
                targetSeason = 1,
                image = differentImg,
            ),
        )
        assertTrue(
            TelegramAnimeParser.matchesSeriesVideo(
                video = vid(200, "Show Name Zero E01.mkv"),
                targetGroupKey = "showname",
                targetSeriesTitle = "Show Name",
                targetKind = TelegramAnimeParser.SeriesKind.MAIN,
                targetSeason = 1,
            ) == null,
        )
    }

    @Test
    fun treatsHyphensAndUnderscoresAsSpacesInPosterSearch() {
        assertEquals(
            "show name test",
            TelegramAnimeParser.buildPosterSearchQuery("Show-Name_Test"),
        )
        val chunks = TelegramAnimeParser.buildPosterSearchChunks("Spy-x_Family", rawSeason = 2)
        assertTrue(chunks.first() == "spy x family S02")
        assertTrue("spy x family II" in chunks)
        assertTrue("spy x family" in chunks)
        assertFalse(chunks.any { it.contains("-") || it.contains("_") })
    }

    @Test
    fun parsesLeadingBareEpisodeNumbersWithoutBreakingNumericTitles() {
        val cheat1 = TelegramAnimeParser.parse("1 I Got a Cheat Skill in Another World and Became Unrivaled in The.mkv")
        assertEquals(1f, cheat1.episodeNumber)
        assertEquals("igotacheatskillinanotherworldandbecameunrivaledinthe", cheat1.baseKey)

        val dating12 = TelegramAnimeParser.parse("12 Trapped in a Dating Sim The World of Otome Games is Tough for.mkv")
        assertEquals(12f, dating12.episodeNumber)
        assertEquals("trappedinadatingsimtheworldofotomegamesistoughfor", dating12.baseKey)

        val underscored = TelegramAnimeParser.parse("03_I_Got_a_Cheat_Skill_in_Another_World.mp4")
        assertEquals(3f, underscored.episodeNumber)

        val eightySix = TelegramAnimeParser.parse("86 Eighty Six E05.mkv")
        assertEquals(5f, eightySix.episodeNumber)
        assertEquals("86eightysix", eightySix.baseKey)

        val posterEightySix = TelegramAnimeParser.parse("86 Eighty Six", allowLeadingBareEpisode = false)
        assertNull(posterEightySix.episodeNumber)
        assertEquals("86eightysix", posterEightySix.baseKey)
    }

    @Test
    fun handlesRomanNumeralsInSeasonsEpisodesAndPosters() {
        val overlord2 = TelegramAnimeParser.parse("Overlord II E01.mkv")
        assertEquals("overlord", overlord2.baseKey)
        assertEquals(2, overlord2.effectiveSeason)
        assertEquals(1f, overlord2.episodeNumber)

        val overlord4 = TelegramAnimeParser.parse("Overlord IV - 05.mkv")
        assertEquals("overlord", overlord4.baseKey)
        assertEquals(4, overlord4.effectiveSeason)
        assertEquals(5f, overlord4.episodeNumber)

        val unicode2 = TelegramAnimeParser.parse("Mob Psycho 100 \u2161 E03.mkv")
        assertEquals("mobpsycho100", unicode2.baseKey)
        assertEquals(2, unicode2.effectiveSeason)
        assertEquals(3f, unicode2.episodeNumber)

        val season3 = TelegramAnimeParser.parse("Show Name Season III Ep IV.mkv")
        assertEquals("showname", season3.baseKey)
        assertEquals(3, season3.effectiveSeason)
        assertEquals(4f, season3.episodeNumber)

        val ova2 = TelegramAnimeParser.parse("Show Name OVA II.mkv")
        assertEquals(TelegramAnimeParser.SeriesKind.OVA, ova2.kind)
        assertEquals(2f, ova2.episodeNumber)

        val spy = TelegramAnimeParser.parse("Spy x Family E01.mkv")
        assertEquals("spyxfamily", spy.baseKey)
        assertEquals(1, spy.effectiveSeason)
    }

    @Test
    fun fallsBackToClosestMatchedPosterWhenExactSearchMisses() {
        val typoTitle = "Rascal Does Not Dream of aA Dreaming Girl"
        val typoKey = TelegramAnimeParser.normalizeBaseKey(typoTitle)
        val bunnyImg = img(1, caption = "Rascal Does Not Dream of Bunny Girl Senpai")
        val dreamingImg = img(2, caption = "Rascal Does Not Dream of a Dreaming Girl")
        val unrelatedImg = img(3, caption = "Attack on Titan")

        assertNull(
            TelegramAnimeParser.matchesSeriesPoster(
                targetSeriesTitle = typoTitle,
                targetGroupKey = typoKey,
                targetKind = TelegramAnimeParser.SeriesKind.MAIN,
                targetSeason = 1,
                image = dreamingImg,
            ),
        )

        val closest = TelegramAnimeParser.findClosestPosterMatch(
            targetSeriesTitle = typoTitle,
            targetGroupKey = typoKey,
            targetKind = TelegramAnimeParser.SeriesKind.MAIN,
            targetSeason = 1,
            candidates = listOf(unrelatedImg, bunnyImg, dreamingImg),
        )
        assertEquals(2L, closest?.messageId)

        val noMatch = TelegramAnimeParser.findClosestPosterMatch(
            targetSeriesTitle = typoTitle,
            targetGroupKey = typoKey,
            targetKind = TelegramAnimeParser.SeriesKind.MAIN,
            targetSeason = 1,
            candidates = listOf(unrelatedImg),
        )
        assertNull(noMatch)
    }

    @Test
    fun groupsHundredsOfItemsQuickly() {
        val videos = (1..500).map { i ->
            val showNum = (i % 25) + 1
            val epNum = (i / 25) + 1
            vid(i.toLong(), "[Group] Sample Anime $showNum S01E${epNum.toString().padStart(2, '0')} [1080p].mkv")
        }
        val images = (1..50).map { i ->
            img((1000 + i).toLong(), caption = "Sample Anime $i")
        }
        val startNs = System.nanoTime()
        val groups = TelegramAnimeParser.groupVideosAndPosters(videos, images)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertEquals(25, groups.size)
        assertTrue(elapsedMs < 2000L, "Expected fast grouping under 2000ms, took ${elapsedMs}ms")
    }

    @Test
    fun parsesTightLeadingHyphenEpisodesWhilePreservingHyphenTitles() {
        val arch3 = TelegramAnimeParser.parse("3-An Archdemon's Dilemma How to Love Your Elf Bride.mp4")
        assertEquals(3f, arch3.episodeNumber)
        assertEquals("anarchdemonsdilemmahowtoloveyourelfbride", arch3.baseKey)

        val arch9 = TelegramAnimeParser.parse("9-An Archdemon's Dilemma How to Love Your Elf Bride.mp4")
        assertEquals(9f, arch9.episodeNumber)
        assertEquals(arch3.baseKey, arch9.baseKey)

        val ghost = TelegramAnimeParser.parse("07-Ghost E02.mkv")
        assertEquals("07ghost", ghost.baseKey)
        assertEquals(2f, ghost.episodeNumber)

        val ghostNoEp = TelegramAnimeParser.parse("07-Ghost.mkv")
        assertEquals("07ghost", ghostNoEp.baseKey)
        assertNull(ghostNoEp.episodeNumber)
    }

    @Test
    fun extractsEpisodeFromCaptionMetadataLines() {
        val parsed = TelegramAnimeParser.parseVideo(
            vid(
                msgId = 42L,
                fileName = "An Archdemon's Dilemma.mp4",
                caption = "An Archdemon's Dilemma\nEpisode 07\n720p",
            ),
        )
        assertEquals(7f, parsed.episodeNumber)
    }

    @Test
    fun computesAdaptiveChunkPlanFormula() {
        val small = Td.planChunks(45L * 1024L * 1024L)
        assertEquals(1, small.chunkMb)
        assertEquals(45, small.totalChunks)

        val medium = Td.planChunks(150L * 1024L * 1024L)
        assertEquals(3, medium.chunkMb)
        assertEquals(50, medium.totalChunks)

        val large = Td.planChunks(1500L * 1024L * 1024L)
        assertEquals(5, large.chunkMb)
        assertEquals(300, large.totalChunks)
    }
}
