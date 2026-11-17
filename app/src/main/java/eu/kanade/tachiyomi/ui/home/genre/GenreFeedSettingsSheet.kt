package eu.kanade.tachiyomi.ui.home.genre

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreFeedSettingsSheet(
    onDismissRequest: () -> Unit,
    selectionMode: FeedGenreSelectionMode,
    onSelectionModeChange: (FeedGenreSelectionMode) -> Unit,
    discoveredGenres: List<DiscoveredGenre>,
    selectedGenres: Set<String>,
    onToggleGenre: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onFetchGenres: () -> Unit = {},
    isDiscovering: Boolean = false,
    sourceCount: Int = 0,
    feedSources: List<FeedSourceOption> = emptyList(),
    prioritySourceIds: List<Long> = emptyList(),
    onTogglePrioritySource: (Long) -> Unit = {},
    onClearPrioritySources: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var sourcesExpanded by remember { mutableStateOf(false) }

    val filteredGenres = remember(discoveredGenres, searchQuery) {
        if (searchQuery.isBlank()) {
            discoveredGenres
        } else {
            val query = searchQuery.trim().lowercase()
            discoveredGenres.filter {
                it.displayName.lowercase().contains(query) || it.key.contains(query)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Genre Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isDiscovering) {
                            "Scanning live filters..."
                        } else {
                            "${discoveredGenres.size} live genres from $sourceCount source${if (sourceCount == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactFetchButton(
                        isDiscovering = isDiscovering,
                        onClick = onFetchGenres,
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Collapsible Added Sources (top 3 priority + random fallback)
            AddedSourcesPriorityCard(
                feedSources = feedSources,
                prioritySourceIds = prioritySourceIds,
                expanded = sourcesExpanded,
                onToggleExpanded = { sourcesExpanded = !sourcesExpanded },
                onToggleSourcePriority = onTogglePrioritySource,
                onClearPriorities = onClearPrioritySources,
            )

            Spacer(Modifier.height(12.dp))

            // Compact modern scope switcher
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    width = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScopeOptionPill(
                        label = "Selected (${selectedGenres.size})",
                        selected = selectionMode == FeedGenreSelectionMode.SELECTED,
                        onClick = { onSelectionModeChange(FeedGenreSelectionMode.SELECTED) },
                        modifier = Modifier.weight(1f),
                    )
                    ScopeOptionPill(
                        label = "All Genres (${discoveredGenres.size})",
                        selected = selectionMode == FeedGenreSelectionMode.ALL,
                        onClick = { onSelectionModeChange(FeedGenreSelectionMode.ALL) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (selectionMode == FeedGenreSelectionMode.SELECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedGenres.isEmpty()) "None selected" else "${selectedGenres.size} active",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        TextButton(
                            onClick = onSelectAll,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text("All", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = onDeselectAll,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text("None", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search live genres...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                if (filteredGenres.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isDiscovering) {
                                "Fetching live genres from feed sources..."
                            } else if (discoveredGenres.isEmpty()) {
                                "No live genres found yet. Tap Fetch to scan feed sources."
                            } else {
                                "No genres match \"$searchQuery\""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        items(
                            items = filteredGenres,
                            key = { it.key },
                            contentType = { "genre_setting_row" },
                        ) { genre ->
                            val isSelected = genre.key in selectedGenres
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleGenre(genre.key) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleGenre(genre.key) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = genre.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Text(
                                        text = "${genre.sourceBindings.size} src",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Showing all ${discoveredGenres.size} live genres discovered from your feed sources.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScopeOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactFetchButton(
    isDiscovering: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !isDiscovering,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isDiscovering) {
                val infiniteTransition = rememberInfiniteTransition(label = "fetch_spin")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "fetch_rotation",
                )
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = "Fetch Genres",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = "Fetch Genres",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = if (isDiscovering) "Fetching" else "Fetch",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddedSourcesPriorityCard(
    feedSources: List<FeedSourceOption>,
    prioritySourceIds: List<Long>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSourcePriority: (Long) -> Unit,
    onClearPriorities: () -> Unit,
) {
    val activeRankSummary = remember(feedSources, prioritySourceIds) {
        if (prioritySourceIds.isEmpty()) {
            "Auto (card source)"
        } else {
            prioritySourceIds.mapIndexedNotNull { idx, id ->
                val name = feedSources.firstOrNull { it.sourceId == id }?.sourceName ?: return@mapIndexedNotNull null
                "${rankLabel(idx)} $name"
            }.ifEmpty { listOf("Auto (card source)") }.joinToString(" • ")
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        border = BorderStroke(
            width = 0.75.dp,
            color = if (prioritySourceIds.isNotEmpty()) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Added Sources Priority",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "${feedSources.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = activeRankSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (prioritySourceIds.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse added sources" else "Expand added sources",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tap up to 3 sources (1st → 2nd → 3rd), then random fallback.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (prioritySourceIds.isNotEmpty()) {
                            TextButton(
                                onClick = onClearPriorities,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    if (feedSources.isEmpty()) {
                        Text(
                            text = "No live feed sources detected yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 188.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(
                                items = feedSources,
                                key = { "priority-src-${it.sourceId}" },
                                contentType = { "priority_source_row" },
                            ) { source ->
                                val rankIdx = prioritySourceIds.indexOf(source.sourceId)
                                val isSelected = rankIdx >= 0
                                Surface(
                                    onClick = { onToggleSourcePriority(source.sourceId) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    border = BorderStroke(
                                        width = 0.75.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = source.sourceName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isSelected) {
                                            val badgeContainer = when (rankIdx) {
                                                0 -> MaterialTheme.colorScheme.primary
                                                1 -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.tertiary
                                            }
                                            val badgeContent = when (rankIdx) {
                                                0 -> MaterialTheme.colorScheme.onPrimary
                                                1 -> MaterialTheme.colorScheme.onSecondary
                                                else -> MaterialTheme.colorScheme.onTertiary
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(7.dp),
                                                color = badgeContainer,
                                            ) {
                                                Text(
                                                    text = rankLabel(rankIdx),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeContent,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Tap to set",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun rankLabel(index: Int): String = when (index) {
    0 -> "1st"
    1 -> "2nd"
    else -> "3rd"
}
