package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.settings.screen.telegram.TelegramScreen
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.telegram.Td
import eu.kanade.tachiyomi.data.telegram.TelegramChannels
import eu.kanade.tachiyomi.data.telegram.TelegramLinks
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

object ExternalStorageAndServicesScreen : Screen() {

    private const val KB_BYTES = 1024.0
    private const val MB_BYTES = 1024.0 * 1024.0
    private const val GB_BYTES = 1024.0 * 1024.0 * 1024.0

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val accentColor = LocalSettingsAccentColor.current
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()
        val telegramPrefs = remember { Injekt.get<TelegramPreferences>() }

        val auth by Td.auth.collectAsState()
        val me by Td.me.collectAsState()
        val chats by Td.chats.collectAsState()
        val channelsRaw by telegramPrefs.channels().collectAsStatePref()
        val linksRaw by telegramPrefs.links().collectAsStatePref()

        val channels = remember(channelsRaw) { TelegramChannels.decode(channelsRaw) }
        val links = remember(linksRaw) { TelegramLinks.decode(linksRaw) }

        var cacheBytes by remember { mutableLongStateOf(0L) }
        var confirmClear by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            Td.init(context)
            cacheBytes = withContext(Dispatchers.IO) { Td.cacheSizeBytes(context) }
        }

        val telegramSubtitle = remember(auth, me, chats.size, channels.size, links.size) {
            when (auth) {
                is Td.Auth.Ready -> buildString {
                    append("Signed in as ")
                    append(me.ifBlank { "Telegram" })
                    append(" • ")
                    append(chats.size)
                    append(" chats")
                    if (channels.isNotEmpty() || links.isNotEmpty()) {
                        append(" • ")
                        append(channels.size)
                        append(" channels")
                    }
                }
                else -> if (channels.isNotEmpty() || links.isNotEmpty()) {
                    "${channels.size} public channels • ${links.size} links • Tap to browse or sign in"
                } else {
                    "Stream videos from Telegram chats, public channels & t.me links"
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear Telegram cache") },
                text = {
                    Text(
                        "Delete cached Telegram video files (${formatBytes(cacheBytes)})? " +
                            "Your signed-in account, saved channels and links will be kept.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            scope.launch {
                                val freed = withContext(Dispatchers.IO) { Td.clearCache(context) }
                                cacheBytes = withContext(Dispatchers.IO) { Td.cacheSizeBytes(context) }
                                context.toast("Cleared ${formatBytes(freed)} of Telegram cache")
                            }
                        },
                    ) {
                        Text("Clear")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        val topBarState = rememberTopAppBarState()
        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = "External storage and services",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            hazeEnabled = hazeEnabled,
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentPadding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp + contentPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "external-services") {
                    MoreSection(
                        title = "Services",
                        accentColor = accentColor,
                    ) {
                        MoreItem(
                            title = "Telegram",
                            subtitle = telegramSubtitle,
                            icon = Icons.AutoMirrored.Outlined.Send,
                            iconTint = accentColor,
                            onClick = { navigator.push(TelegramScreen()) },
                        )
                    }
                }
                item(key = "external-storage-cache") {
                    MoreSection(
                        title = "Storage",
                        accentColor = accentColor,
                    ) {
                        MoreItem(
                            title = "Clear Telegram media cache",
                            subtitle = if (cacheBytes > 0L) {
                                "Using ${formatBytes(cacheBytes)} for streamed Telegram files"
                            } else {
                                "No cached Telegram video files"
                            },
                            icon = Icons.Outlined.DeleteOutline,
                            iconTint = accentColor,
                            onClick = {
                                if (cacheBytes > 0L) {
                                    confirmClear = true
                                } else {
                                    context.toast("Telegram cache is already empty")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= GB_BYTES -> String.format(Locale.US, "%.2f GB", bytes / GB_BYTES)
            bytes >= MB_BYTES -> String.format(Locale.US, "%.1f MB", bytes / MB_BYTES)
            bytes >= KB_BYTES -> String.format(Locale.US, "%.0f KB", bytes / KB_BYTES)
            else -> "$bytes B"
        }
    }
}
