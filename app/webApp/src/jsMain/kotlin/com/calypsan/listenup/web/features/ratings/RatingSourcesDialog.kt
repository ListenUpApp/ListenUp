package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ModalDialog
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * The ListenUp score's sources, opened from its row: "Combined from N sources", the score itself, then one
 * row per source — its own average, its count, its share as a percent and a bar, and for an outside catalog
 * when it was last updated ("Updated 3 days ago", counted from [nowMs]; nothing when the server never said,
 * or when [nowMs] is unknown). An admin refreshes from a quiet ghost action at the foot; Close is the primary.
 *
 * Each row is read as one sentence ("Audible: 4.8 average from 11k ratings, 62% of the score."), with its
 * figures hidden from a screen reader, which would otherwise read a run of numbers and nothing saying which
 * is which. Each average is on the source's own curve, not ListenUp's.
 */
@Composable
fun RatingSourcesDialog(
    open: Boolean,
    ready: BookRatingsUiState.Ready,
    nowMs: Long,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val score = ready.external

    ModalDialog(open = true, title = "Ratings", onDismiss = onDismiss) {
        if (score != null && score.sourceCount >= 2) {
            Div(attrs = { classes("rt-combined") }) { Text("Combined from ${score.sourceCount} sources") }
        }
        if (score != null) {
            Div(attrs = { classes("rt-src-head") }) {
                Span(attrs = { classes("rt-src-big") }) {
                    Span(attrs = {
                        classes("rt-star")
                        attr(ARIA_HIDDEN, "true")
                    }) { Text("★ ") }
                    Text(averageLabel(score.average))
                }
                Span(attrs = { classes("rt-src-sub") }) { Text("ListenUp score · ${ratingsCount(score.count)}") }
            }
        }
        Div(attrs = { classes("rt-srcs") }) {
            ready.breakdown.forEach { rating ->
                key(rating.source) {
                    SourceRow(
                        label = sourceDisplayName(rating.source),
                        average = rating.average,
                        count = rating.count,
                        share = score?.shares?.get(ScoreSource.Outside(rating.source)),
                        updated = updatedLabel(rating.fetchedAtMs, nowMs),
                    )
                }
            }
            val listeners = ready.listeners
            val listenersShare = score?.shares?.get(ScoreSource.Listeners)
            if (listeners != null && listenersShare != null) {
                SourceRow(
                    label = "Your listeners",
                    average = listeners.averageHalfStars / 2,
                    count = listeners.count,
                    share = listenersShare,
                    updated = null,
                )
            }
        }
        Div(attrs = { classes("dlg-actions", "rt-src-acts") }) {
            if (ready.canRefresh) RefreshAction(isRefreshing = ready.isRefreshingExternal, onRefresh = onRefresh)
            Button(kind = ButtonKind.Primary, onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun SourceRow(
    label: String,
    average: Double,
    count: Int,
    share: Double?,
    updated: String?,
) {
    val percent = share?.let { "${(it * PERCENT).roundToInt()}%" }
    val spoken =
        buildString {
            append("$label: ${averageLabel(average)} average from ${ratingsCount(count)}")
            percent?.let { append(", $it of the score") }
            append(".")
            updated?.let { append(" $it.") }
        }
    Div(attrs = { classes("rt-src") }) {
        Span(attrs = { classes("rt-sr") }) { Text(spoken) }
        Span(attrs = {
            classes("rt-src-name")
            attr(ARIA_HIDDEN, "true")
        }) { Text(label) }
        Span(attrs = {
            classes("rt-src-avg")
            attr(ARIA_HIDDEN, "true")
        }) {
            Span(attrs = { classes("rt-star") }) { Text("★ ") }
            Text(averageLabel(average))
        }
        Span(attrs = {
            classes("rt-src-fig")
            attr(ARIA_HIDDEN, "true")
        }) {
            Span(attrs = { classes("rt-src-line") }) {
                Span { Text(ratingsCount(count)) }
                percent?.let { Span { Text(it) } }
            }
            percent?.let { width ->
                Span(attrs = { classes("rt-bar") }) {
                    Span(attrs = { style { property("width", width) } })
                }
            }
            updated?.let { Span(attrs = { classes("rt-upd") }) { Text(it) } }
        }
    }
}

/** "Updated today", "Updated yesterday", "Updated 3 days ago"; null when either time is unknown. */
internal fun updatedLabel(
    fetchedAtMs: Long?,
    nowMs: Long,
): String? {
    if (fetchedAtMs == null || nowMs <= 0L) return null
    return when (val days = RatingLabels.daysSince(fetchedAtMs, nowMs)) {
        0 -> "Updated today"
        1 -> "Updated yesterday"
        else -> "Updated $days days ago"
    }
}

/** The source name every platform shows: "Audible", "Hardcover", "Goodreads". */
internal fun sourceDisplayName(source: ExternalRatingSource): String =
    when (source) {
        ExternalRatingSource.AUDIBLE -> "Audible"
        ExternalRatingSource.HARDCOVER -> "Hardcover"
        ExternalRatingSource.GOODREADS -> "Goodreads"
        ExternalRatingSource.UNKNOWN -> "Unknown"
    }

/** A share of the score, as a whole percent. */
private const val PERCENT = 100

private const val ARIA_HIDDEN = "aria-hidden"
