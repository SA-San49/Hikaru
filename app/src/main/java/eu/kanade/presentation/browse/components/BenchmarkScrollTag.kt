package eu.kanade.presentation.browse.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.paging.compose.LazyPagingItems

/** Expose a scroll target only after the first real result is loaded, never for placeholders. */
internal fun LazyPagingItems<*>.benchmarkScrollTag(): Modifier {
    return if (itemCount > 0 && peek(0) != null) {
        Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("browse_grid")
    } else {
        Modifier
    }
}
