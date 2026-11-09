package eu.kanade.tachiyomi.util.system

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

object CoverColorObserver {
    private val _vibrantColors = MutableStateFlow<PersistentMap<Long, Int>>(persistentHashMapOf())
    val vibrantColors = _vibrantColors.asStateFlow()

    private val _ratios = MutableStateFlow<PersistentMap<Long, Float>>(persistentHashMapOf())
    val ratios = _ratios.asStateFlow()

    fun update(animeId: Long, color: Int) {
        if (_vibrantColors.value[animeId] == color) return
        _vibrantColors.update { it.put(animeId, color) }
    }

    fun updateRatio(animeId: Long, ratio: Float) {
        val currentRatio = _ratios.value[animeId]
        if (currentRatio != null && abs(currentRatio - ratio) < 0.01f) return
        _ratios.update { it.put(animeId, ratio) }
    }

    fun restore(ratios: Map<Long, Float>, colors: Map<Long, Int>) {
        _ratios.value = persistentHashMapOf<Long, Float>().putAll(ratios)
        _vibrantColors.value = persistentHashMapOf<Long, Int>().putAll(colors)
    }

    fun get(animeId: Long): Int? = _vibrantColors.value[animeId]

    fun getRatio(animeId: Long): Float? = _ratios.value[animeId]
}
