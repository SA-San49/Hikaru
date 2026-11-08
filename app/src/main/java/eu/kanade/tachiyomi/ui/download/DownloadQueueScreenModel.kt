package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<DownloadHeaderItem>())
    val state = _state.asStateFlow()

    internal val queueSummary = combine(
        downloadManager.queueState,
        downloadManager.progressFlow().sample(1000).map { Unit }.onStart { emit(Unit) },
    ) { downloads, _ -> createQueueSummary(downloads) }
        .stateIn(
            screenModelScope,
            SharingStarted.Eagerly,
            createQueueSummary(downloadManager.queueState.value),
        )

    val alwaysUseInternalDownloader = downloadPreferences.alwaysUseInternalDownloader().stateIn(screenModelScope)

    fun toggleAlwaysUseInternalDownloader() {
        downloadPreferences.alwaysUseInternalDownloader().set(!alwaysUseInternalDownloader.value)
    }

    lateinit var controllerBinding: DownloadListBinding

    /**
     * Adapter containing the active downloads.
     */
    var adapter: DownloadAdapter? = null

    /**
     * Map of jobs for active downloads.
     */
    private val progressJobs = mutableMapOf<Download, Job>()

    val listener = object : DownloadAdapter.DownloadItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val downloads = adapter.headerItems.flatMap { header ->
                adapter.getSectionItems(header).map { item ->
                    (item as DownloadItem).download
                }
            }
            reorder(downloads)
        }

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) ?: return
            if (item is DownloadItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val headerItems = adapter?.headerItems ?: return
                        val newDownloads = mutableListOf<Download>()
                        headerItems.forEach { headerItem ->
                            headerItem as DownloadHeaderItem
                            if (headerItem == item.header) {
                                headerItem.removeSubItem(item)
                                if (menuItem.itemId == R.id.move_to_top) {
                                    headerItem.addSubItem(0, item)
                                } else {
                                    headerItem.addSubItem(item)
                                }
                            }
                            newDownloads.addAll(headerItem.subItems.map { it.download })
                        }
                        reorder(newDownloads)
                    }
                    R.id.cancel_download -> cancel(listOf(item.download))
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            downloadManager.queueState
                .map { downloads ->
                    downloads
                        .groupBy { it.source }
                        .map { entry ->
                            DownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size).apply {
                                addSubItems(0, entry.value.map { DownloadItem(it, this) })
                            }
                        }
                }
                .collectLatest { newList -> _state.update { newList } }
        }
    }

    private fun createQueueSummary(downloads: List<Download>): DownloadQueueSummary {
        val active = downloads.filter {
            it.status == Download.State.DOWNLOADING ||
                it.status == Download.State.MERGING ||
                it.status == Download.State.DECRYPTING ||
                it.status == Download.State.FINALIZING
        }
        val waitingCount = downloads.count {
            it.status == Download.State.QUEUE || it.status == Download.State.PAUSED
        }
        val failedCount = downloads.count { it.status == Download.State.ERROR }
        val remainingCount = active.size + waitingCount + failedCount
        val activeEtas = active
            .filter { it.status == Download.State.DOWNLOADING }
            .mapNotNull { it.etaSecondsRemaining }
        val maxConcurrentDownloads = downloadManager.maxConcurrentDownloads
        val progress = if (remainingCount == 0) {
            0
        } else {
            (active.sumOf { it.progress } / remainingCount).coerceIn(0, 100)
        }

        return DownloadQueueSummary(
            remainingCount = remainingCount,
            activeCount = active.size,
            waitingCount = waitingCount,
            failedCount = failedCount,
            progressPercent = progress,
            speedBytesPerSecond = active.sumOf { it.speedBytesPerSecond.toLong().coerceAtLeast(0L) },
            etaSeconds = DownloadQueueEtaEstimator.estimate(
                activeEtasSeconds = activeEtas,
                waitingCount = waitingCount,
                maxConcurrentDownloads = maxConcurrentDownloads,
                activeCount = active.size,
            ),
        )
    }

    override fun onDispose() {
        progressJobs.values.forEach { it.cancel() }
        progressJobs.clear()
        adapter = null
    }

    val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(screenModelScope, SharingStarted.Eagerly, false)

    fun getDownloadStatusFlow() = downloadManager.statusFlow()
        .sample(1000)
    fun getDownloadProgressFlow() = downloadManager.progressFlow()
        .sample(1000)

    fun startDownloads() = downloadManager.startDownloads()
    fun pauseDownloads() = downloadManager.pauseDownloads()
    fun clearQueue() = downloadManager.clearQueue()
    fun reorder(downloads: List<Download>) = downloadManager.reorderQueue(downloads)
    fun cancel(downloads: List<Download>) = downloadManager.cancelQueuedDownloads(downloads)

    fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newDownloads = mutableListOf<Download>()
        adapter.headerItems.forEach { headerItem ->
            headerItem as DownloadHeaderItem
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) reverse()
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorder(newDownloads)
    }

    fun onStatusChange(download: Download) {
        onUpdateProgress(download)
        onUpdateDownloadedPages(download)
    }

    private fun onUpdateProgress(download: Download) {
        getHolder(download)?.notifyProgress()
        getHolder(download)?.notifyDownloadedPages()
    }

    fun onUpdateDownloadedPages(download: Download) {
        getHolder(download)?.notifyDownloadedPages()
        getHolder(download)?.notifyProgress()
    }

    private fun getHolder(download: Download): DownloadHolder? {
        return if (::controllerBinding.isInitialized) {
            controllerBinding.root.findViewHolderForItemId(download.episode.id) as? DownloadHolder
        } else null
    }
}
