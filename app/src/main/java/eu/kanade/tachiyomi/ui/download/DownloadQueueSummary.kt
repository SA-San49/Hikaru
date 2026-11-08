package eu.kanade.tachiyomi.ui.download

import kotlin.math.max

internal data class DownloadQueueSummary(
    val remainingCount: Int = 0,
    val activeCount: Int = 0,
    val waitingCount: Int = 0,
    val failedCount: Int = 0,
    val progressPercent: Int = 0,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
)

internal object DownloadQueueEtaEstimator {

    /** Estimates the time until the queue drains, using active download ETAs as a service-time sample. */
    fun estimate(
        activeEtasSeconds: List<Long>,
        waitingCount: Int,
        maxConcurrentDownloads: Int,
        activeCount: Int = activeEtasSeconds.size,
    ): Long? {
        val etas = activeEtasSeconds.filter { it >= 0L }.sorted()
        if (etas.isEmpty()) return null

        val medianEta = etas[etas.size / 2]
        val concurrentLimit = maxConcurrentDownloads.coerceAtLeast(1)
        val remainingCount = max(etas.size, activeCount) + waitingCount.coerceAtLeast(0)
        val waves = (remainingCount + concurrentLimit - 1) / concurrentLimit
        val queueEstimate = if (waves == 0) 0L else {
            medianEta.coerceAtMost(Long.MAX_VALUE / waves) * waves
        }
        return max(etas.last(), queueEstimate)
    }
}
