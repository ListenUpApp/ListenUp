package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton

private const val NEXT_COVER = 40

/** "Next in the series" (§2.3, the finish card's): a quiet row that opens that book. */
@Composable
internal fun NextInSeriesButton(
    next: NextInSeries,
    onOpenBook: (String) -> Unit,
) {
    DomButton(attrs = {
        classes("home-hero-next")
        attr("type", "button")
        onClick { onOpenBook(next.bookId) }
    }) {
        Cover(
            title = next.title,
            imageUrl = coverUrl(next.bookId, next.coverHash, width = NEXT_COVER * 2),
            size = NEXT_COVER,
            decorative = true,
            overlay = { RestrictedMarker(next.bookId, compact = true) },
        )
        Span(attrs = { classes("home-hero-next-text") }) {
            Span(attrs = { classes("home-hero-next-k") }) { Text("Next in the series") }
            Span(attrs = { classes("home-hero-next-t") }) { Text(next.title) }
        }
    }
}
