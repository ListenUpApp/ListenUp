package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.calypsan.listenup.client.design.components.BookCoverFallback
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage

/**
 * A series' cover at [size]: its own (or lead book's) image when there is one, else the same
 * gradient-and-title fallback a coverless book wears, seeded by the series id. Decorative — the row
 * or card around it names the series.
 */
@Composable
internal fun SeriesCoverThumb(
    seriesId: String,
    name: String,
    coverPath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size).clip(MaterialTheme.shapes.small)) {
        BookCoverFallback(title = name, author = "", seed = seriesId, modifier = Modifier.matchParentSize())
        if (coverPath != null) {
            ListenUpAsyncImage(path = coverPath, contentDescription = null, modifier = Modifier.matchParentSize())
        }
    }
}
