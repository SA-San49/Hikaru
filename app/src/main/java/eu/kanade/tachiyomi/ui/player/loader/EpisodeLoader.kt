package eu.kanade.tachiyomi.ui.player.loader

import eu.kanade.domain.episode.model.toSEpisode
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.util.subtitles.StremioSubtitleResolver
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toSAnime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.source.localanime.LocalAnimeSource
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import tachiyomi.source.localanime.io.LocalFileEntry
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to retrieve the hosters for a given episode.
 */
class EpisodeLoader {
    companion object {

        private val SUBTITLE_EXTENSIONS = hashSetOf("srt", "ass", "ssa", "vtt", "sub")

        /**
         * Returns a list of hosters of an [episode] based on the type of [source] used.
         *
         * @param episode the episode being parsed.
         * @param anime the anime of the episode.
         * @param source the source of the anime.
         */
        suspend fun getHosters(episode: Episode, anime: Anime, source: AnimeSource): List<Hoster> {
            // Check local source first to avoid redundant SAF scans and handle subfolder/title mismatches
            if (source is LocalAnimeSource) {
                return getHostersOnLocal(episode)
            }
            val isDownloaded = isDownload(episode, anime)
            return when {
                isDownloaded -> getHostersOnDownloaded(episode, anime, source)
                source is AnimeHttpSource -> getHostersOnHttp(episode, source)
                else -> error("source not supported")
            }
        }

        /**
         * Returns true if the given [episode] is downloaded.
         *
         * @param episode the episode being parsed.
         * @param anime the anime of the episode.
         */
        fun isDownload(episode: Episode, anime: Anime): Boolean {
            val downloadManager: DownloadManager = Injekt.get()
            return downloadManager.isEpisodeDownloaded(
                episode.name,
                episode.scanlator,
                anime.title,
                anime.source,
                skipCache = true,
            )
        }

        private fun checkHasHosters(source: AnimeHttpSource): Boolean {
            var current: Class<in AnimeHttpSource> = source.javaClass
            while (true) {
                if (current == ParsedAnimeHttpSource::class.java ||
                    current == AnimeHttpSource::class.java ||
                    current == AnimeSource::class.java
                ) {
                    return false
                }
                if (current.declaredMethods.any {
                        it.name in
                            listOf("getHosterList", "hosterListRequest", "hosterListParse")
                    }
                ) {
                    return true
                }
                current = current.superclass ?: return false
            }
        }

        /**
         * Returns a list of hosters when the [episode] is online.
         *
         * @param episode the episode being parsed.
         * @param source the online source of the episode.
         */
        private suspend fun getHostersOnHttp(episode: Episode, source: AnimeHttpSource): List<Hoster> {
            // TODO(1.6): Remove else block when dropping support for ext lib <1.6
            return if (checkHasHosters(source)) {
                source.getHosterList(episode.toSEpisode())
                    .let { source.run { it.sortHosters() } }
            } else {
                source.getVideoList(episode.toSEpisode())
                    .let { source.run { it.sortVideos() } }
                    .toHosterList()
            }
        }

        /**
         * Returns the hoster when the [episode] is downloaded.
         *
         * @param episode the episode being parsed.
         * @param anime the anime of the episode.
         * @param source the source of the anime.
         */
        private fun getHostersOnDownloaded(
            episode: Episode,
            anime: Anime,
            source: AnimeSource,
        ): List<Hoster> {
            val downloadManager: DownloadManager = Injekt.get()
            return try {
                val video = downloadManager.buildVideo(source, anime, episode)
                listOf(video).toHosterList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Returns the hoster when the [episode] is from local source.
         *
         * @param episode the episode being parsed.
         */
        private fun getHostersOnLocal(
            episode: Episode,
        ): List<Hoster> {
            return try {
                val parts = episode.url.split('/', limit = 2)
                if (parts.size < 2) return emptyList()
                val animeDirName = parts[0]
                val episodeRelativePath = parts[1]

                val fileSystem: LocalAnimeSourceFileSystem = Injekt.get()
                val videoEntry = fileSystem.findEpisodeEntry(animeDirName, episodeRelativePath)
                    ?: fileSystem.findEpisodeByNameWithoutExtension(animeDirName, episode.name)
                    ?: return emptyList()

                val siblingEntries = resolveSiblingEntries(fileSystem, animeDirName, episodeRelativePath)
                val baseNameLower = videoEntry.nameWithoutExtension.lowercase()
                val subtitleTracks = siblingEntries
                    .filter { entry ->
                        !entry.isDirectory &&
                            entry.extension.lowercase() in SUBTITLE_EXTENSIONS &&
                            matchesSubtitleName(entry.nameWithoutExtension.lowercase(), baseNameLower)
                    }
                    .map { sub ->
                        Track(sub.uri.toString(), sub.nameWithoutExtension)
                    }

                val video = Video(
                    videoUrl = videoEntry.uri.toString(),
                    videoTitle = "Local source: ${episode.url}",
                    subtitleTracks = subtitleTracks,
                    initialized = true,
                ).apply {
                    status = Video.State.READY
                }
                listOf(video).toHosterList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun resolveSiblingEntries(
            fileSystem: LocalAnimeSourceFileSystem,
            animeDirName: String,
            episodeRelativePath: String,
        ): List<LocalFileEntry> {
            val topEntries = fileSystem.listAnimeDirectoryEntries(animeDirName)
            val slashIndex = episodeRelativePath.indexOf('/')
            if (slashIndex < 0) return topEntries
            val subDirName = episodeRelativePath.substring(0, slashIndex)
            val subDir = topEntries.firstOrNull { it.isDirectory && it.name == subDirName } ?: return topEntries
            return fileSystem.listSubDirectoryEntries(animeDirName, subDir)
        }

        private fun matchesSubtitleName(subBaseLower: String, videoBaseLower: String): Boolean {
            if (subBaseLower == videoBaseLower) return true
            if (!subBaseLower.startsWith(videoBaseLower) || subBaseLower.length <= videoBaseLower.length) {
                return false
            }
            val nextChar = subBaseLower[videoBaseLower.length]
            return nextChar == '.' || nextChar == '_' || nextChar == ' '
        }

        /**
         * Returns a list of videos of a [hoster] based on the type of [source] used.
         * Note that for every type of episode except non-downloaded online, `videoList`
         * will be set to null.
         *
         * @param source the source of the anime.
         * @param hoster the hoster.
         */
        private suspend fun getVideos(source: AnimeSource, hoster: Hoster): List<Video> {
            val videos = when {
                hoster.videoList != null && source is AnimeHttpSource -> hoster.videoList!!.parseVideoUrls(source)
                hoster.videoList != null -> hoster.videoList!!
                source is AnimeHttpSource -> getVideosOnHttp(source, hoster)
                else -> error("source not supported")
            }

            val sortedVideos = if (source is AnimeHttpSource) {
                source.run { videos.sortVideos() }
            } else {
                videos
            }

            return sortedVideos.resolveSubtitles()
        }

        private suspend fun List<Video>.resolveSubtitles(): List<Video> {
            return this.map { video ->
                if (video.subtitleTracks.isEmpty()) return@map video

                val resolvedTracks = video.subtitleTracks.flatMap { StremioSubtitleResolver.resolve(it) }
                if (resolvedTracks == video.subtitleTracks) return@map video

                video.copy(subtitleTracks = resolvedTracks)
            }
        }

        /**
         * Returns a list of hosters when the [episode] is online.
         *
         * @param source the online source of the episode.
         * @param hoster the hoster.
         */
        private suspend fun getVideosOnHttp(source: AnimeHttpSource, hoster: Hoster): List<Video> {
            return source.getVideoList(hoster)
                .parseVideoUrls(source)
        }

        // TODO(1.6): Remove after ext lib bump
        private suspend fun List<Video>.parseVideoUrls(source: AnimeHttpSource): List<Video> {
            return this.map { video ->
                if (video.videoUrl != "null") return@map video

                val newVideoUrl = source.getVideoUrl(video)
                video.copy(videoUrl = newVideoUrl)
            }
        }

        suspend fun loadHosterVideos(source: AnimeSource, hoster: Hoster): HosterState {
            return try {
                val videos = getVideos(source, hoster)
                HosterState.Ready(hoster.hosterName, videos, List(videos.size) { Video.State.QUEUE })
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                HosterState.Error(hoster.hosterName)
            }
        }
    }
}
