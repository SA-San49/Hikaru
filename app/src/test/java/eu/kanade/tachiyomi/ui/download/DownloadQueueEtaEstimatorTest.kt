package eu.kanade.tachiyomi.ui.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DownloadQueueEtaEstimatorTest {

    @Test
    fun `returns no estimate before a download has a measured ETA`() {
        assertNull(
            DownloadQueueEtaEstimator.estimate(
                activeEtasSeconds = emptyList(),
                waitingCount = 5,
                maxConcurrentDownloads = 4,
            ),
        )
    }

    @Test
    fun `does not add a new wave when queued work fits current capacity`() {
        assertEquals(
            120L,
            DownloadQueueEtaEstimator.estimate(
                activeEtasSeconds = listOf(120L),
                waitingCount = 3,
                maxConcurrentDownloads = 4,
            ),
        )
    }

    @Test
    fun `estimates additional waves using active download service time`() {
        assertEquals(
            20L,
            DownloadQueueEtaEstimator.estimate(
                activeEtasSeconds = listOf(10L, 10L),
                waitingCount = 2,
                maxConcurrentDownloads = 2,
            ),
        )
    }

    @Test
    fun `includes local active stages and safely clamps concurrency`() {
        assertEquals(
            40L,
            DownloadQueueEtaEstimator.estimate(
                activeEtasSeconds = listOf(10L),
                waitingCount = 1,
                maxConcurrentDownloads = 0,
                activeCount = 3,
            ),
        )
    }
}
