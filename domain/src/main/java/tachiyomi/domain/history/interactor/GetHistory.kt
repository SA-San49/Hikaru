package tachiyomi.domain.history.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class GetHistory(
    private val repository: HistoryRepository,
) {

    suspend fun await(animeId: Long): List<History> {
        return repository.getHistoryByAnimeId(animeId)
    }

    suspend fun awaitRecent(limit: Int = 30): List<HistoryWithRelations> {
        return repository.getRecentHistory(limit.coerceIn(1, 100))
    }

    fun subscribe(query: String): Flow<List<HistoryWithRelations>> {
        return repository.getHistory(query)
    }
}
