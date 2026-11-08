package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

data object DownloadQueueScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { DownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        val queueSummary by screenModel.queueSummary.collectAsState()
        val isDownloaderRunning by screenModel.isDownloaderRunning.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (downloadCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$downloadCount",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = { AnimeActions(screenModel, downloadList) },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                if (downloadCount > 0) {
                    ExtendedFloatingActionButton(
                        text = {
                            val id = if (isDownloaderRunning) MR.strings.action_pause else MR.strings.action_resume
                            Text(text = stringResource(id))
                        },
                        icon = {
                            val icon = if (isDownloaderRunning) Icons.Outlined.Pause else Icons.Filled.PlayArrow
                            Icon(imageVector = icon, contentDescription = null)
                        },
                        onClick = {
                            if (isDownloaderRunning) screenModel.pauseDownloads() else screenModel.startDownloads()
                        },
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            val scope = rememberCoroutineScope()

            if (downloadList.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .nestedScroll(nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueSummaryCard(
                    summary = queueSummary,
                    isRunning = isDownloaderRunning,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        screenModel.controllerBinding = DownloadListBinding.inflate(
                            LayoutInflater.from(context),
                        )
                        screenModel.adapter = DownloadAdapter(screenModel.listener)
                        screenModel.controllerBinding.root.adapter = screenModel.adapter
                        screenModel.adapter?.isHandleDragEnabled = true
                        screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)
                        ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                        scope.launchUI {
                            screenModel.getDownloadStatusFlow().collect(screenModel::onStatusChange)
                        }
                        scope.launchUI {
                            screenModel.getDownloadProgressFlow().collect(screenModel::onUpdateDownloadedPages)
                        }
                        screenModel.controllerBinding.root
                    },
                    update = {
                        screenModel.adapter?.updateDataSet(downloadList)
                    },
                )
            }
        }
    }

    @Composable
    private fun QueueSummaryCard(
        summary: DownloadQueueSummary,
        isRunning: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Queue overview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${summary.activeCount} active · ${summary.waitingCount} waiting · ${summary.failedCount} failed · ${summary.remainingCount} remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${summary.progressPercent}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LinearProgressIndicator(
                    progress = { summary.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    QueueMetric(
                        title = "Queue ETA · estimate",
                        value = summary.etaSeconds?.let(::formatQueueEta)
                            ?: when {
                                isRunning -> "Calculating…"
                                summary.waitingCount > 0 -> "Paused"
                                else -> "—"
                            },
                        modifier = Modifier.weight(1f),
                    )
                    QueueMetric(
                        title = "Combined speed",
                        value = formatQueueSpeed(summary.speedBytesPerSecond),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Composable
    private fun QueueMetric(
        title: String,
        value: String,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier = modifier) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    private fun AnimeActions(
        animeScreenModel: DownloadQueueScreenModel,
        animeDownloadList: List<DownloadHeaderItem>,
    ) {
        val alwaysUseInternal by animeScreenModel.alwaysUseInternalDownloader.collectAsState()
        
        if (animeDownloadList.isNotEmpty()) {
            var sortExpanded by remember { mutableStateOf(false) }
            val onDismissRequest = { sortExpanded = false }
            DropdownMenu(
                expanded = sortExpanded,
                onDismissRequest = onDismissRequest,
            ) {
                NestedMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                    children = { closeMenu ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                            onClick = {
                                animeScreenModel.reorderQueue(
                                    { it.download.episode.dateUpload },
                                    true,
                                )
                                closeMenu()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                            onClick = {
                                animeScreenModel.reorderQueue(
                                    { it.download.episode.dateUpload },
                                    false,
                                )
                                closeMenu()
                            },
                        )
                    },
                )
                NestedMenuItem(
                    text = {
                        Text(
                            text = stringResource(MR.strings.action_order_by_episode_number),
                        )
                    },
                    children = { closeMenu ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                            onClick = {
                                animeScreenModel.reorderQueue(
                                    { it.download.episode.episodeNumber },
                                    false,
                                )
                                closeMenu()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                            onClick = {
                                animeScreenModel.reorderQueue(
                                    { it.download.episode.episodeNumber },
                                    true,
                                )
                                closeMenu()
                            },
                        )
                    },
                )
            }

            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_sort),
                        icon = Icons.AutoMirrored.Outlined.Sort,
                        onClick = { sortExpanded = true },
                    ),
                    AppBar.OverflowAction(
                        title = if (alwaysUseInternal) {
                            stringResource(MR.strings.action_use_hls_download)
                        } else {
                            stringResource(MR.strings.action_use_normal_download)
                        },
                        onClick = { animeScreenModel.toggleAlwaysUseInternalDownloader() },
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_cancel_all),
                        onClick = { animeScreenModel.clearQueue() },
                    ),
                ),
            )
        }
    }
}

private fun formatQueueEta(seconds: Long): String {
    if (seconds <= 0L) return "Finishing"
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "~${days}d ${hours}h"
        hours > 0 -> "~${hours}h ${minutes}m"
        minutes > 0 -> "~${minutes}m ${seconds % 60}s"
        else -> "<1 min"
    }
}

private fun formatQueueSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond > 0L -> "$bytesPerSecond B/s"
    else -> "—"
}
