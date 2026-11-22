package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.components.ExpressiveSectionHeader

private val GroupHeaderTopPadding = 18.dp
private val GroupHeaderHorizontalPadding = 16.dp

@Composable
fun PreferenceGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GroupHeaderHorizontalPadding,
                end = GroupHeaderHorizontalPadding,
                top = GroupHeaderTopPadding,
            ),
    ) {
        ExpressiveSectionHeader(title = title)
    }
}
