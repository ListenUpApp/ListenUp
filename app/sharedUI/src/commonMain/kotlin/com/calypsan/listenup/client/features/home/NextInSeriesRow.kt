package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.NextInSeries
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_next_in_series
import org.jetbrains.compose.resources.stringResource

private val NextCover = 44.dp

/** "Next in the series" (§2.3, the finish card's): an opaque list row on `surfaceContainerLowest`, no chevron, that opens that book. */
@Composable
internal fun NextInSeriesRow(
    next: NextInSeries,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                BookCoverImage(
                    bookId = next.bookId,
                    coverPath = next.coverPath,
                    coverHash = next.coverHash,
                    contentDescription = null,
                    title = next.title,
                    // Decorative: the row's text names the book.
                    modifier = Modifier.size(NextCover).clip(MaterialTheme.shapes.small).clearAndSetSemantics {},
                )
                RestrictedBookMarker(next.bookId, Modifier.align(Alignment.TopStart), compact = true)
            }
            Column {
                Text(
                    text = stringResource(Res.string.home_next_in_series),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = next.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
