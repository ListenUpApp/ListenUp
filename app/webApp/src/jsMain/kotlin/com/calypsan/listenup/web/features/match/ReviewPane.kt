package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.presentation.match.WhatWillChange
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.focusAsLanding
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.EventTarget
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * The Review pane (W-03): the match's header, What will change, the sections in canvas order, and the
 * Apply bar pinned to the bottom of the pane.
 *
 * The Review heading is focusable by script: picking a match lands focus on it, and Skip to Apply —
 * straight after it — is the way past a long review for a keyboard.
 */
@Suppress("LongParameterList")
@Composable
internal fun ReviewPane(
    review: ReviewUiState,
    bookId: String,
    viewerId: String?,
    session: BookMatchSession,
    reloaded: Boolean,
    onBack: (CandidateUi) -> Unit,
    onRetry: (CandidateUi) -> Unit,
) {
    H2(attrs = {
        classes("bmx-pane-t")
        attr("id", REVIEW_HEADING_ID)
        attr("tabindex", "-1")
    }) { Text("Review") }

    when (review) {
        ReviewUiState.NoneChosen -> {
            EmptyState(title = "Pick a match to review it.", look = EmptyLook.Inline)
        }

        is ReviewUiState.Loading -> {
            ReviewHeader(review.candidate, onBack)
            Div(attrs = {
                classes("skel", "bmx-skel")
                attr("aria-hidden", "true")
            })
            P(attrs = { classes("bmx-note") }) { Text("Loading this match…") }
        }

        is ReviewUiState.Failed -> {
            ReviewHeader(review.candidate, onBack)
            EmptyState(
                title = "Couldn't load this match",
                body = review.error.message,
                look = EmptyLook.Inset,
                action = {
                    Div(attrs = { classes("bmx-fail-acts") }) {
                        Button(kind = ButtonKind.Secondary, onClick = { onRetry(review.candidate) }) { Text("Retry") }
                    }
                },
            )
        }

        is ReviewUiState.Ready -> {
            // Straight after the heading: past a long review in one press.
            DomButton(attrs = {
                classes("lnk", "bmx-skip")
                attr("type", "button")
                onClick { event -> focusById(APPLY_ID, event.target) }
            }) { Text("Skip to Apply") }
            ReviewHeader(review.candidate, onBack)
            if (reloaded) P(attrs = { classes("bmx-err") }) { Text(REVIEW_RELOADED) }
            Summary(review)
            ReviewSections(review, bookId, viewerId, session)
            ApplyBar(review, session.apply)
        }
    }
}

/** The match being reviewed: its cover, Best match, title, metadata and where it was found. */
@Composable
private fun ReviewHeader(
    candidate: CandidateUi,
    onBack: (CandidateUi) -> Unit,
) {
    Div(attrs = { classes("bmx-head") }) {
        Art(url = candidate.coverUrl, big = true)
        Div(attrs = { classes("bmx-head-m") }) {
            if (candidate.isBest) {
                Span(
                    attrs = { classes("bmx-badges") },
                ) { Span(attrs = { classes("bmx-badge", "is-best") }) { Text("Best match") } }
            }
            Span(attrs = { classes("bmx-head-t") }) { Text(candidate.title) }
            candidateMetaText(
                candidate,
            ).takeIf { it.isNotBlank() }?.let { Span(attrs = { classes("bmx-row-meta") }) { Text(it) } }
            if (candidate.foundIn.isNotEmpty()) {
                Span(attrs = { classes("bmx-row-found") }) { Text(foundInText(candidate.foundIn)) }
            }
        }
        Button(kind = ButtonKind.Ghost, onClick = { onBack(candidate) }) { Text("Back to results") }
    }
}

/** What will change: each count is a button to its section, for a reader who wants that part only. */
@Composable
private fun Summary(review: ReviewUiState.Ready) {
    val items = summaryItems(review.summary, review)
    if (items.isEmpty()) return
    Ul(attrs = {
        classes("bmx-sum")
        attr("aria-label", "What will change")
    }) {
        items.forEach { item ->
            Li {
                DomButton(attrs = {
                    classes("bmx-sum-i")
                    attr("type", "button")
                    onClick { event -> focusById(item.sectionId, event.target) }
                }) {
                    item.count?.let { Span(attrs = { classes("bmx-sum-n") }) { Text(it) } }
                    Text(item.label)
                }
            }
        }
    }
}

private class SummaryItem(
    val count: String?,
    val label: String,
    val sectionId: String,
)

private fun summaryItems(
    summary: WhatWillChange,
    review: ReviewUiState.Ready,
): List<SummaryItem> {
    val sameCount = review.alreadySame.size + if (review.lengthAlreadySame) 1 else 0
    val labels = summary.labelsAdded + summary.labelsRemoved
    return listOfNotNull(
        summary.changeCount.takeIf { it > 0 }?.let {
            SummaryItem(
                "$it",
                if (it ==
                    1
                ) {
                    "change"
                } else {
                    "changes"
                },
                SECTION_CHANGES,
            )
        },
        summary.gapCount.takeIf { it > 0 }?.let {
            SummaryItem(
                "$it",
                if (it ==
                    1
                ) {
                    "gap filled"
                } else {
                    "gaps filled"
                },
                SECTION_GAPS,
            )
        },
        summary.coverSource?.let { SummaryItem("Cover", "from ${it.label}", SECTION_COVER) },
        labels.takeIf { it > 0 }?.let { SummaryItem("$it", "genres and moods", SECTION_LABELS) },
        summary.chapterNameCount.takeIf { it > 0 }?.let { SummaryItem("$it", "chapter names", SECTION_CHAPTERS) },
        summary.keptEditedCount.takeIf { it > 0 }?.let { SummaryItem("$it", "kept as you edited it", SECTION_EDITED) },
        sameCount.takeIf { it > 0 }?.let { SummaryItem("$it", "already match", SECTION_SAME) },
    ).filter { item -> item.sectionId != SECTION_CHAPTERS || review.chapterNames is ChapterNamesUi.Available }
}

/**
 * Pinned to the bottom of the review pane: what Apply will write, in words that update in place, and
 * Apply changes. While Apply runs the button says so and ignores a press — `aria-disabled`, so the
 * focus the press put on it stays there. A failure is said above it, with "Nothing was changed."
 */
@Composable
private fun ApplyBar(
    review: ReviewUiState.Ready,
    onApply: () -> Unit,
) {
    Div(attrs = { classes("bmx-apply") }) {
        review.applyError?.let { error -> P(attrs = { classes("bmx-err") }) { Text(nothingChanged(error.message)) } }
        Div(attrs = { classes("bmx-bar") }) {
            Div(attrs = { classes("bmx-bar-text") }) {
                Span(attrs = { classes("bmx-bar-sum") }) { Text(applyBarText(review.applyBar)) }
                Span(
                    attrs = { classes("bmx-note") },
                ) { Text("Nothing changes until you apply. You can undo it afterwards.") }
            }
            Button(
                kind = ButtonKind.Primary,
                onClick = onApply,
                pressable = review.applyBar.canApply && !review.applying,
                attrs = { attr("id", APPLY_ID) },
            ) { Text(if (review.applying) APPLYING else "Apply changes") }
        }
    }
}

/**
 * Focuses the element with [id] in the document [from] lives in, making a heading focusable by script
 * first.
 */
internal fun focusById(
    id: String,
    from: EventTarget?,
) {
    val owner = (from as? Node)?.ownerDocument ?: return
    (owner.getElementById(id) as? HTMLElement)?.let(::focusAsLanding)
}

internal const val SECTION_COVER = "bmx-sec-cover"
internal const val SECTION_CHANGES = "bmx-sec-changes"
internal const val SECTION_GAPS = "bmx-sec-gaps"
internal const val SECTION_EDITED = "bmx-sec-edited"
internal const val SECTION_LABELS = "bmx-sec-labels"
internal const val SECTION_CHAPTERS = "bmx-sec-chapters"
internal const val SECTION_SAME = "bmx-sec-same"
