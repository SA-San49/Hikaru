package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.components.ExpressiveStatusPill
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.settings.screen.telegram.TelegramScreen
import eu.kanade.presentation.theme.LocalSettingsAccentColor
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.telegram.Td
import eu.kanade.tachiyomi.data.telegram.TelegramBrowseSources
import eu.kanade.tachiyomi.data.telegram.TelegramChannels
import eu.kanade.tachiyomi.data.telegram.TelegramLinks
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ExternalStorageAndServicesScreen : Screen() {

    val TelegramBrandBlue = Color(0xFF24A1DE)

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
        val browseSourcesRaw by telegramPrefs.browseSources().collectAsStatePref()
        val cacheMedia by telegramPrefs.cacheMedia().collectAsStatePref()

        val channels = remember(channelsRaw) { TelegramChannels.decode(channelsRaw) }
        val links = remember(linksRaw) { TelegramLinks.decode(linksRaw) }
        val browseSources = remember(browseSourcesRaw) { TelegramBrowseSources.parse(browseSourcesRaw) }

        var gearDialogOpen by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            Td.init(context)
        }

        fun setCacheMediaEnabled(enabled: Boolean) {
            telegramPrefs.cacheMedia().set(enabled)
            if (!enabled) {
                scope.launch {
                    withContext(Dispatchers.IO) { Td.purgeMediaFiles(context) }
                    context.toast("Telegram media caching disabled")
                }
            } else {
                context.toast("Telegram media caching enabled")
            }
        }

        val isSignedIn = auth is Td.Auth.Ready
        val telegramSubtitle = remember(auth, me, chats.size, channels.size, links.size, browseSources.size) {
            when (auth) {
                is Td.Auth.Ready -> buildString {
                    append("Signed in as ")
                    append(me.ifBlank { "Telegram" })
                    append(" • ")
                    append(chats.size)
                    append(" chats")
                    if (browseSources.isNotEmpty()) {
                        append(" • ")
                        append(browseSources.size)
                        append(" in Browse")
                    }
                }
                else -> if (channels.isNotEmpty() || links.isNotEmpty()) {
                    "${channels.size} public channels • ${links.size} links • Tap to browse or sign in"
                } else {
                    "Stream videos from Telegram chats, channels & bots"
                }
            }
        }

        if (gearDialogOpen) {
            TelegramMediaCacheDialog(
                cacheMedia = cacheMedia,
                onToggleCacheMedia = ::setCacheMediaEnabled,
                onDismiss = { gearDialogOpen = false },
            )
        }

        val topBarState = rememberTopAppBarState()
        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = "External storage and services",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = "Settings",
                                    icon = Icons.Outlined.Settings,
                                    onClick = { gearDialogOpen = true },
                                ),
                            ),
                        )
                    },
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
                        Column {
                            TelegramConnectorRow(
                                title = "Telegram",
                                subtitle = telegramSubtitle,
                                isSignedIn = isSignedIn,
                                accentColor = accentColor,
                                onClick = { navigator.push(TelegramScreen()) },
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 74.dp, end = 20.dp)
                                    .height(0.5.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                    ),
                            )
                            TelegramMediaCacheToggleRow(
                                checked = cacheMedia,
                                onCheckedChange = ::setCacheMediaEnabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TelegramBrandLogo(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        color = ExternalStorageAndServicesScreen.TelegramBrandBlue,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_24dp),
                contentDescription = "Telegram",
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
private fun TelegramConnectorRow(
    title: String,
    subtitle: String,
    isSignedIn: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TelegramBrandLogo()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isSignedIn) {
            ExpressiveStatusPill(
                text = "CONNECTED",
                containerColor = accentColor.copy(alpha = 0.2f),
                contentColor = accentColor,
                borderColor = accentColor.copy(alpha = 0.45f),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun TelegramMediaCacheToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Cache Telegram media",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (checked) {
                    "ON • Keep streamed video chunks on device storage"
                } else {
                    "OFF • Never cache media on disk (streamed chunks auto-delete)"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun TelegramMediaCacheDialog(
    cacheMedia: Boolean,
    onToggleCacheMedia: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("External Services Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleCacheMedia(!cacheMedia) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cache Telegram media",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (cacheMedia) {
                                    "Enabled: Streamed video data is kept in storage."
                                } else {
                                    "Disabled (Default): Streamed video data is never cached " +
                                        "and is removed after playback."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = cacheMedia,
                            onCheckedChange = onToggleCacheMedia,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
