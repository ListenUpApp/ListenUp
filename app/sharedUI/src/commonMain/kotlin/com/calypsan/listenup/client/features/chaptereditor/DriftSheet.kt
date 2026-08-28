package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_at
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_first
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_none
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_second
import listenup.composeapp.generated.resources.chapter_editor_drift_apply
import listenup.composeapp.generated.resources.chapter_editor_drift_intro
import listenup.composeapp.generated.resources.chapter_editor_drift_needs_playhead
import listenup.composeapp.generated.resources.chapter_editor_drift_needs_selection
import listenup.composeapp.generated.resources.chapter_editor_drift_pin_first
import listenup.composeapp.generated.resources.chapter_editor_drift_pin_second
import listenup.composeapp.generated.resources.chapter_editor_drift_refused_anchors
import listenup.composeapp.generated.resources.chapter_editor_drift_refused_inverted
import listenup.composeapp.generated.resources.chapter_editor_drift_summary
import listenup.composeapp.generated.resources.chapter_editor_drift_summary_single
import listenup.composeapp.generated.resources.chapter_editor_drift_title
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * The guided drift flow.
 *
 * Drift is bulk error — a scrape whose offset grows across the book — and the point of this panel
 * is that correcting 311 boundaries costs two pins rather than 311 edits. You pin a chapter you can
 * *hear* is right, then a second one later on, and everything between is interpolated.
 *
 * Pinning is deliberately tied to the playhead rather than to a typed timestamp. The user's
 * evidence is what they just heard, and asking them to transcribe that into a number would add a
 * transcription error to a flow whose entire purpose is removing error.
 *
 * Nothing here mutates anything. The panel renders a proposal and its ghosts; only [onApply]
 * commits, and it commits precisely the previewed set.
 *
 * @param drift the open flow's state.
 * @param chapters the working set, for naming anchors by their visible number.
 * @param hasSelection whether a chapter is chosen to pin.
 * @param hasPlayhead whether this book's transport is available to pin at.
 * @param onPin pin the selected chapter at the current playhead.
 * @param onApply commit the previewed correction.
 * @param onCancel abandon the proposal.
 * @param modifier Modifier for the panel.
 */
@Composable
fun DriftSheet(
    drift: ChapterEditorUiState.DriftState,
    chapters: List<Chapter>,
    hasSelection: Boolean,
    hasPlayhead: Boolean,
    onPin: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ready = drift.preview as? DriftPreview.Ready
    val canPin = hasSelection && hasPlayhead

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.chapter_editor_drift_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            stringResource(Res.string.chapter_editor_drift_intro),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        AnchorLine(
            label = stringResource(Res.string.chapter_editor_drift_anchor_first),
            anchor = drift.proposal?.first,
            chapters = chapters,
        )
        AnchorLine(
            label = stringResource(Res.string.chapter_editor_drift_anchor_second),
            anchor = drift.proposal?.second,
            chapters = chapters,
        )

        DriftMessage(
            preview = drift.preview,
            hasSelection = hasSelection,
            hasPlayhead = hasPlayhead,
            ready = ready,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
            TextButton(onClick = onPin, enabled = canPin) {
                Text(
                    stringResource(
                        if (drift.proposal == null) {
                            Res.string.chapter_editor_drift_pin_first
                        } else {
                            Res.string.chapter_editor_drift_pin_second
                        },
                    ),
                )
            }
            // Enabled only on a Ready preview: a refusal must not be one tap from mangling the book.
            Button(onClick = onApply, enabled = ready != null) {
                Text(stringResource(Res.string.chapter_editor_drift_apply))
            }
        }
    }
}

/**
 * What the proposal would do, or why it cannot — never a silently disabled button.
 *
 * A mis-set anchor is the likeliest mistake in this flow, and "Apply is greyed out" says nothing
 * about which of the two pins is wrong.
 */
@Composable
private fun DriftMessage(
    preview: DriftPreview?,
    hasSelection: Boolean,
    hasPlayhead: Boolean,
    ready: DriftPreview.Ready?,
) {
    val colors = MaterialTheme.colorScheme
    when {
        // Says which of the two prerequisites is missing rather than a generic "can't pin" —
        // they need different actions from the user, and guessing wrong wastes their time.
        !hasSelection -> {
            Text(
                stringResource(Res.string.chapter_editor_drift_needs_selection),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        !hasPlayhead -> {
            Text(
                stringResource(Res.string.chapter_editor_drift_needs_playhead),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        ready != null -> {
            Text(
                // A single anchor is a constant shift, so there is no spread to report — quoting
                // one would be describing a slope the correction does not have.
                if (ready.spreadMs == 0L) {
                    stringResource(
                        Res.string.chapter_editor_drift_summary_single,
                        ready.affectedCount,
                        ChapterTimeFormat.offset(ready.firstOffsetMs),
                    )
                } else {
                    stringResource(
                        Res.string.chapter_editor_drift_summary,
                        ready.affectedCount,
                        ChapterTimeFormat.offset(ready.spreadMs),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
        }

        preview is DriftPreview.Refused -> {
            Text(
                stringResource(
                    when (preview.reason) {
                        DriftRefusal.UnusableAnchors -> Res.string.chapter_editor_drift_refused_anchors
                        DriftRefusal.InvertedAnchors -> Res.string.chapter_editor_drift_refused_inverted
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }

        else -> {
            Unit
        }
    }
}

/** One anchor, named by the number the user can see rather than by its id. */
@Composable
private fun AnchorLine(
    label: String,
    anchor: ChapterAnchor?,
    chapters: List<Chapter>,
) {
    val colors = MaterialTheme.colorScheme
    val position = anchor?.let { a -> chapters.indexOfFirst { it.id == a.chapterId } } ?: -1

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Text(
            if (anchor == null || position < 0) {
                stringResource(Res.string.chapter_editor_drift_anchor_none)
            } else {
                stringResource(
                    Res.string.chapter_editor_drift_anchor_at,
                    position + 1,
                    ChapterTimeFormat.precise(anchor.trueStartMs),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
    }
}
