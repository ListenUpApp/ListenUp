package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_reader_scrubber_page_count
import listenup.composeapp.generated.resources.book_reader_scrubber_page_label
import org.jetbrains.compose.resources.stringResource

/**
 * Page scrubber for the reader dock.
 *
 * Displays the current page label · a draggable [Slider] · the total page count.
 * While the user is dragging, the displayed fraction tracks the thumb; on drag end
 * [onSeekToIndex] is called with the resolved 0-based page index so the screen can
 * jump to the target page. When not dragging the fraction is re-derived from
 * [currentPage] / [pageCount] so it always reflects real document position.
 *
 * @param currentPage 1-based current page number.
 * @param pageCount Total page count. Renders nothing when ≤ 0.
 * @param onSeekToIndex Called on drag end with the resolved 0-based target index.
 */
@Composable
internal fun ReaderPageScrubber(
    currentPage: Int,
    pageCount: Int,
    onSeekToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 0) return

    val syncedFraction = if (pageCount > 0) currentPage.toFloat() / pageCount.toFloat() else 0f

    var isDragging by remember { mutableStateOf(false) }
    var sliderFraction by remember(currentPage, pageCount) { mutableFloatStateOf(syncedFraction) }

    // Sync from external page position when not actively dragging.
    if (!isDragging) {
        sliderFraction = syncedFraction
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.book_reader_scrubber_page_label, currentPage),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderFraction,
            onValueChange = { fraction ->
                isDragging = true
                sliderFraction = fraction
            },
            onValueChangeFinished = {
                isDragging = false
                onSeekToIndex(scrubberPageIndex(sliderFraction, pageCount))
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.book_reader_scrubber_page_count, pageCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
