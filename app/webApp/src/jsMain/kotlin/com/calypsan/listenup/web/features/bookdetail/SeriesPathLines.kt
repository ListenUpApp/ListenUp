package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where this book sits among series: one line per series, "Cosmere › Mistborn › Mistborn Era 1 #1".
 *
 * Every name is a real button into that series — the ancestors as much as the series the book is in,
 * because "what else is in the Cosmere?" is the question a path invites. A line wraps only between
 * names (each `<li>` refuses to break inside), and no name is ever truncated. The "›" between names
 * is CSS pseudo-content, so a screen reader hears a list of series rather than a run of chevrons.
 *
 * A path four or more levels deep folds its middle into a "…" button (en.json's
 * `series.path_expand_a11y`) that brings the hidden names back in place; the root, the parent and
 * the series itself always stay visible, since those are the ones that place the book.
 *
 * Renders nothing at all, not an empty group, when the book is in no series.
 */
@Composable
internal fun SeriesPathLines(
    paths: List<BookSeriesPath>,
    onOpenSeries: (String) -> Unit,
) {
    if (paths.isEmpty()) return
    // en.json's series.path_a11y
    Div(attrs = {
        classes("bd-series")
        attr("role", "group")
        attr("aria-label", "Series path")
    }) {
        paths.forEach { path -> key(path.seriesId) { PathLine(path, onOpenSeries) } }
    }
}

@Composable
private fun PathLine(
    path: BookSeriesPath,
    onOpenSeries: (String) -> Unit,
) {
    var expanded by remember(path.seriesId) { mutableStateOf(false) }
    val folds = !expanded && path.ancestors.size >= FOLD_AT_ANCESTORS

    Ol(attrs = { classes("bd-series-line") }) {
        if (folds) {
            Crumb(path.ancestors.first(), onOpenSeries)
            Li {
                Button(attrs = {
                    classes("bd-series-fold")
                    attr("type", "button")
                    // en.json's series.path_expand_a11y
                    attr("aria-label", "Show the full series path")
                    onClick { expanded = true }
                }) { Text("…") }
            }
            Crumb(path.ancestors.last(), onOpenSeries)
        } else {
            path.ancestors.forEach { crumb -> Crumb(crumb, onOpenSeries) }
        }
        Li {
            Button(attrs = {
                classes("bd-series-link", "is-own")
                attr("type", "button")
                onClick { onOpenSeries(path.seriesId) }
            }) {
                Text(path.seriesName)
                path.sequence?.let { position ->
                    Span(attrs = { classes("bd-series-seq") }) { Text("#$position") }
                }
            }
        }
    }
}

@Composable
private fun Crumb(
    crumb: SeriesCrumb,
    onOpenSeries: (String) -> Unit,
) {
    Li {
        Button(attrs = {
            classes("bd-series-link")
            attr("type", "button")
            onClick { onOpenSeries(crumb.id) }
        }) { Text(crumb.name) }
    }
}

/** Three ancestors above the series make four levels — the depth at which the middle folds. */
private const val FOLD_AT_ANCESTORS = 3
