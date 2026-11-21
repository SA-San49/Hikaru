package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
private const val GROUP_LAZY_ITEM_COUNT = 3
private val ScreenCardHorizontalPadding = 16.dp
private val CardInnerVerticalPadding = 4.dp
private val GroupBottomSpacing = 8.dp
private val StandaloneTopSpacing = 12.dp
private val StandaloneBottomSpacing = 8.dp

private sealed interface PreferenceBlock {
    data class Group(val group: Preference.PreferenceGroup) : PreferenceBlock
    data class StandaloneCard(val items: List<Preference.PreferenceItem<*>>) : PreferenceBlock
    data class StandaloneFree(val item: Preference.PreferenceItem<*>) : PreferenceBlock
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
                is PreferenceBlock.Group -> {
                    item(key = "group-header-$blockIndex-${block.group.title}") {
                        PreferenceGroupHeader(title = block.group.title)
                    }
                    item(key = "group-content-$blockIndex-${block.group.title}") {
                        PreferenceCardContainer(
                            items = block.group.preferenceItems,
                            highlightKey = highlightKey,
                            useContainer = useContainer,
                        )
                    }
                    item(key = "group-spacer-$blockIndex-${block.group.title}") {
                        if (blockIndex < blocks.lastIndex) {
                            Spacer(modifier = Modifier.height(GroupBottomSpacing))
                        }
                    }
                }
                is PreferenceBlock.StandaloneCard -> {
                    item(key = "standalone-card-$blockIndex") {
                        Column(
                            modifier = Modifier.padding(
                                top = if (blockIndex == 0) StandaloneTopSpacing else 0.dp,
                                bottom = if (blockIndex < blocks.lastIndex) StandaloneBottomSpacing else 0.dp,
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
                is PreferenceBlock.StandaloneFree -> {
                    item(key = "standalone-free-$blockIndex-${block.item.title}") {
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
            tonalElevation = 1.dp,
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
            if (showDividers && item.enabled && !isUnboxedPreference(item)) {
                val nextEnabled = items
                    .drop(index + 1)
                    .firstOrNull { it.enabled }
                if (nextEnabled != null && !isUnboxedPreference(nextEnabled)) {
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
    return item is Preference.PreferenceItem.InfoPreference ||
        item is Preference.PreferenceItem.CustomPreference
}

private fun buildPreferenceBlocks(items: List<Preference>): List<PreferenceBlock> {
    val result = mutableListOf<PreferenceBlock>()
    val pendingStandalone = mutableListOf<Preference.PreferenceItem<*>>()

    fun flushPending() {
        if (pendingStandalone.isNotEmpty()) {
            result.add(PreferenceBlock.StandaloneCard(pendingStandalone.toList()))
            pendingStandalone.clear()
        }
    }

    items.forEach { preference ->
        when (preference) {
            is Preference.PreferenceGroup -> {
                if (preference.enabled) {
                    flushPending()
                    result.add(PreferenceBlock.Group(preference))
                }
            }
            is Preference.PreferenceItem<*> -> {
                if (isUnboxedPreference(preference)) {
                    flushPending()
                    result.add(PreferenceBlock.StandaloneFree(preference))
                } else {
                    pendingStandalone.add(preference)
                }
            }
        }
    }
    flushPending()
    return result
}

private fun List<PreferenceBlock>.findHighlightedIndex(highlightKey: String): Int {
    var lazyIndex = 0
    forEach { block ->
        when (block) {
            is PreferenceBlock.Group -> {
                val headerIndex = lazyIndex
                val contentIndex = lazyIndex + 1
                if (block.group.preferenceItems.any { it.title == highlightKey }) {
                    return if (block.group.preferenceItems.firstOrNull()?.title == highlightKey) {
                        headerIndex
                    } else {
                        contentIndex
                    }
                }
                lazyIndex += GROUP_LAZY_ITEM_COUNT
            }
            is PreferenceBlock.StandaloneCard -> {
                if (block.items.any { it.title == highlightKey }) {
                    return lazyIndex
                }
                lazyIndex += 1
            }
            is PreferenceBlock.StandaloneFree -> {
                if (block.item.title == highlightKey) {
                    return lazyIndex
                }
                lazyIndex += 1
            }
        }
    }
    return -1
}
