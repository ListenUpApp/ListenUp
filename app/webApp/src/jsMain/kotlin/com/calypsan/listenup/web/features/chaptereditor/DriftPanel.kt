package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The guided drift flow.
 *
 * Drift is the editor's answer to *bulk* error — a scrape whose offset grows across the book, where
 * fixing 311 boundaries by hand is not a real option. The reader pins two chapters they can hear
 * are right; everything between is interpolated.
 *
 * ⛔ Nothing moves until Apply. This is a proposal: the summary describes what would happen, and
 * abandoning the flow leaves the chapter set exactly as it was.
 *
 * A refusal is shown as a sentence, not a greyed-out button. A mis-set anchor is the likeliest
 * mistake here, and "Apply is disabled" tells the reader nothing about which of the two is wrong.
 */
@Composable
internal fun DriftPanel(
    drift: ChapterEditorUiState.DriftState,
    chapters: List<Chapter>,
    selectedChapterId: String?,
    playheadMs: Long?,
    onPin: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Div(attrs = {
        classes("drift")
        attr("role", "region")
        attr("aria-label", "Fix drift")
    }) {
        H2(attrs = { classes("drift-h") }) { Text("Fix drift") }
        P(attrs = { classes("drift-intro") }) {
            Text(
                "Pin a chapter you can hear is right, then a second one later in the book. " +
                    "Everything between them is corrected.",
            )
        }

        Div(attrs = { classes("drift-anchors") }) {
            AnchorSlot(
                "First anchor",
                drift.proposal?.first?.let { anchorLabel(it.chapterId, it.trueStartMs, chapters) },
            )
            AnchorSlot(
                "Second anchor",
                drift.proposal?.second?.let { anchorLabel(it.chapterId, it.trueStartMs, chapters) },
            )
        }

        // The two things a pin needs, named separately: which boundary, and where it really is.
        // One message covering both would leave the reader guessing which half they are missing.
        when {
            selectedChapterId == null -> {
                P(attrs = { classes("drift-need") }) {
                    Text("Choose a chapter in the list, play to where you hear it start, then pin.")
                }
            }

            playheadMs == null -> {
                P(attrs = { classes("drift-need") }) { Text("Play the book to the spot you can hear, then pin.") }
            }

            else -> {
                // Which anchor the next pin becomes. The ViewModel's rule is that the first pin is
                // the earlier anchor and every later one replaces the second, so the label only
                // ever has two states to name.
                val pinLabel =
                    if (drift.proposal?.first == null) {
                        "Pin first anchor at playhead"
                    } else {
                        "Pin second anchor at playhead"
                    }
                Button(attrs = {
                    classes("btn-o", "drift-pin")
                    attr("type", "button")
                    onClick { onPin() }
                }) { Text(pinLabel) }
            }
        }

        drift.preview?.let { DriftSummary(it) }

        Div(attrs = { classes("drift-acts") }) {
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                onClick { onCancel() }
            }) { Text("Cancel") }
            Button(attrs = {
                classes("btn-c")
                attr("type", "button")
                disabledWhen(drift.preview !is DriftPreview.Ready)
                onClick { onApply() }
            }) { Text("Apply correction") }
        }
    }
}

/** What the pinned anchors would do, or why they cannot. */
@Composable
private fun DriftSummary(preview: DriftPreview) {
    when (preview) {
        is DriftPreview.Ready -> {
            P(attrs = { classes("drift-sum") }) {
                // A single number when there is nothing to spread across — one anchor is the
                // degenerate case, a constant shift, and quoting a spread of 0 would read as
                // "this changes nothing" for a correction that moves every boundary.
                Text(
                    if (preview.spreadMs == 0L) {
                        "${preview.affectedCount} boundaries move by ${ChapterTimeFormat.offset(
                            preview.firstOffsetMs,
                        )}."
                    } else {
                        "${preview.affectedCount} boundaries move. " +
                            "Drift spreads ${ChapterTimeFormat.offset(preview.spreadMs)} across the book."
                    },
                )
            }
        }

        is DriftPreview.Refused -> {
            P(attrs = {
                classes("drift-refused")
                attr("role", "alert")
            }) {
                Text(
                    when (preview.reason) {
                        DriftRefusal.UnusableAnchors -> {
                            "Those anchors cannot be used. Pin two different chapters."
                        }

                        DriftRefusal.InvertedAnchors -> {
                            "The second anchor is earlier than the first. " +
                                "Audio does not run backwards, so one of them is wrong."
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AnchorSlot(
    label: String,
    value: String?,
) {
    Div(attrs = { classes("drift-slot") }) {
        Span(attrs = { classes("drift-slot-l") }) { Text(label) }
        Span(attrs = { classes("drift-slot-v") }) { Text(value ?: "Not pinned") }
    }
}

/** Names an anchor by the number the reader can see, and the time they pinned it at. */
private fun anchorLabel(
    chapterId: String,
    trueStartMs: Long,
    chapters: List<Chapter>,
): String {
    val position = chapters.indexOfFirst { it.id == chapterId }
    val number = if (position < 0) "?" else (position + 1).toString()
    return "Chapter $number · ${ChapterTimeFormat.precise(trueStartMs)}"
}
