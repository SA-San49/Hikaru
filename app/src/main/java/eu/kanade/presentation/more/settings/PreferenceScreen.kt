package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.domain.ui.ContainerStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.components.ExpressiveCardCornerRadius
import eu.kanade.presentation.more.components.ExpressiveDividerHorizontalInset
import eu.kanade.presentation.more.components.ExpressiveDividerStartInset
import eu.kanade.presentation.more.components.ExpressiveItemDivider
import eu.kanade.presentation.more.components.expressiveCardBorder
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

private const val HIGHLIGHT_SCROLL_DELAY_SECONDS = 0.5
private val ScreenCardHorizontalPadding = 16.dp
private val CardInnerVerticalPadding = 4.dp
private val GroupBottomSpacing = 8.dp
private val StandaloneTopSpacing = 12.dp
private val StandaloneBottomSpacing = 8.dp

private sealed interface PreferenceBlock {
    data class GroupHeader(val title: String) : PreferenceBlock
    data class CardGroup(val items: List<Preference.PreferenceItem<*>>, val addBottomSpacing: Boolean) : PreferenceBlock
    data class FreeItem(val item: Preference.PreferenceItem<*>) : PreferenceBlock
}

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen.
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
@Suppress("UnstableCollections")
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val containerStyles by uiPreferences.containerStyles().collectAsState()
    val useContainer = remember(containerStyles) { ContainerStyle.SETTINGS in containerStyles }
    val blocks = remember(items) { buildPreferenceBlocks(items) }

    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = blocks.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(HIGHLIGHT_SCROLL_DELAY_SECONDS.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        blocks.fastForEachIndexed { blockIndex, block ->
            when (block) {
                is PreferenceBlock.GroupHeader -> {
                    item(key = "group-header-$blockIndex-${block.title}") {
                        PreferenceGroupHeader(title = block.title)
                    }
                }
                is PreferenceBlock.CardGroup -> {
                    item(key = "card-group-$blockIndex") {
                        Column(
                            modifier = Modifier.padding(
                                top = if (blockIndex == 0) StandaloneTopSpacing else 0.dp,
                                bottom = if (block.addBottomSpacing && blockIndex < blocks.lastIndex) {
                                    GroupBottomSpacing
                                } else if (blockIndex < blocks.lastIndex) {
                                    StandaloneBottomSpacing
                                } else {
                                    0.dp
                                },
                            ),
                        ) {
                            PreferenceCardContainer(
                                items = block.items,
                                highlightKey = highlightKey,
                                useContainer = useContainer,
                            )
                        }
                    }
                }
                is PreferenceBlock.FreeItem -> {
                    item(key = "free-item-$blockIndex-${block.item.title}") {
                        PreferenceItem(
                            item = block.item,
                            highlightKey = highlightKey,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceCardContainer(
    items: List<Preference.PreferenceItem<*>>,
    highlightKey: String?,
    useContainer: Boolean,
) {
    if (useContainer) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenCardHorizontalPadding),
            shape = RoundedCornerShape(ExpressiveCardCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = expressiveCardBorder(),
            tonalElevation = 0.dp,
        ) {
            PreferenceCardItems(
                items = items,
                highlightKey = highlightKey,
                showDividers = true,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenCardHorizontalPadding),
        ) {
            PreferenceCardItems(
                items = items,
                highlightKey = highlightKey,
                showDividers = false,
            )
        }
    }
}

@Composable
private fun PreferenceCardItems(
    items: List<Preference.PreferenceItem<*>>,
    highlightKey: String?,
    showDividers: Boolean,
) {
    val nextEnabledByIndex = remember(items, showDividers) {
        if (!showDividers) {
            emptyList()
        } else {
            val next = MutableList<Preference.PreferenceItem<*>?>(items.size) { null }
            var upcoming: Preference.PreferenceItem<*>? = null
            for (idx in items.indices.reversed()) {
                next[idx] = upcoming
                if (items[idx].enabled) {
                    upcoming = items[idx]
                }
            }
            next
        }
    }

    Column(
        modifier = Modifier.padding(vertical = CardInnerVerticalPadding),
    ) {
        items.fastForEachIndexed { index, item ->
            key(index, item.title) {
                PreferenceItem(
                    item = item,
                    highlightKey = highlightKey,
                )
            }
            if (showDividers && item.enabled && item !is Preference.PreferenceItem.CustomPreference) {
                val nextEnabled = nextEnabledByIndex.getOrNull(index)
                if (nextEnabled != null && nextEnabled !is Preference.PreferenceItem.CustomPreference) {
                    val insetStart = if (item.icon != null || nextEnabled.icon != null) {
                        ExpressiveDividerStartInset
                    } else {
                        ExpressiveDividerHorizontalInset
                    }
                    ExpressiveItemDivider(insetStart = insetStart)
                }
            }
        }
    }
}

private fun isUnboxedPreference(item: Preference.PreferenceItem<*>): Boolean {
    return item is Preference.PreferenceItem.InfoPreference
}

private fun buildPreferenceBlocks(items: List<Preference>): List<PreferenceBlock> {
    val result = mutableListOf<PreferenceBlock>()
    val pendingCardItems = mutableListOf<Preference.PreferenceItem<*>>()

    fun flushCard(isGroup: Boolean) {
        if (pendingCardItems.isNotEmpty()) {
            result.add(PreferenceBlock.CardGroup(pendingCardItems.toList(), addBottomSpacing = isGroup))
            pendingCardItems.clear()
        }
    }

    items.forEach { preference ->
        when (preference) {
            is Preference.PreferenceGroup -> {
                if (preference.enabled) {
                    flushCard(isGroup = false)
                    result.add(PreferenceBlock.GroupHeader(preference.title))
                    preference.preferenceItems.forEach { groupItem ->
                        if (isUnboxedPreference(groupItem)) {
                            flushCard(isGroup = true)
                            result.add(PreferenceBlock.FreeItem(groupItem))
                        } else {
                            pendingCardItems.add(groupItem)
                        }
                    }
                    flushCard(isGroup = true)
                }
            }
            is Preference.PreferenceItem<*> -> {
                if (isUnboxedPreference(preference)) {
                    flushCard(isGroup = false)
                    result.add(PreferenceBlock.FreeItem(preference))
                } else {
                    pendingCardItems.add(preference)
                }
            }
        }
    }
    flushCard(isGroup = false)
    return result
}

private fun List<PreferenceBlock>.findHighlightedIndex(highlightKey: String): Int {
    forEachIndexed { index, block ->
        when (block) {
            is PreferenceBlock.GroupHeader -> Unit
            is PreferenceBlock.CardGroup -> {
                if (block.items.any { it.title == highlightKey }) {
                    return index
                }
            }
            is PreferenceBlock.FreeItem -> {
                if (block.item.title == highlightKey) {
                    return index
                }
            }
        }
    }
    return -1
}
