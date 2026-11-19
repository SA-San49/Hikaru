package tachiyomi.source.localanime.image

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SAnime
import java.io.InputStream

expect class LocalAnimeSourceCoverManager {

    fun find(animeUrl: String): UniFile?

    fun findInDirectory(animeDir: UniFile): UniFile?

    fun update(anime: SAnime, inputStream: InputStream): UniFile?
}
