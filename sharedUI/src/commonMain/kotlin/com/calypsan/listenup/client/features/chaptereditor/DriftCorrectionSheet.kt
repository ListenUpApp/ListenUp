package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.chapter.DriftResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_drift_add_anchor
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_snapped
import listenup.composeapp.generated.resources.chapter_editor_drift_anchor_unsnapped
import listenup.composeapp.generated.resources.chapter_editor_drift_apply
import listenup.composeapp.generated.resources.chapter_editor_drift_bad_anchors_warning
import listenup.composeapp.generated.resources.chapter_editor_drift_hint
import listenup.composeapp.generated.resources.chapter_editor_drift_inverted_warning
import listenup.composeapp.generated.resources.chapter_editor_drift_locked
import listenup.composeapp.generated.resources.chapter_editor_drift_snap
import listenup.composeapp.generated.resources.chapter_editor_drift_title
import listenup.composeapp.generated.resources.common_cancel

/** [TimeMarker.styleKey] the ghost preview markers this sheet emits are tagged with. */
private const val GHOST_STYLE_KEY = "ghost"

/**
 * One anchor-picking slot in the sheet's local flow. [trueStartMs] is `null` until a Snap sets it
 * — a slot with no true start yet contributes no [ChapterAnchor] to [onApplyDrift]/[onCommitDrift].
 */
private data class AnchorSlot(
    val chapterId: String,
    val trueStartMs: Long? = null,
)

/**
 * Guided drift-correction sheet: lets the user pin the *true* start of a handful of chapters by
 * ear (scrub/play, then Snap), previews the resulting [com.calypsan.listenup.client.domain.chapter.correctDrift]
 * piecewise-linear correction as ghost markers on the Timing lane, and commits it as exactly one
 * undo frame.
 *
 * Opens pre-seeded with two anchor slots — [draft]'s first and last chapters — since a first+last
 * pair is the headline flow `correctDrift`'s N-anchor map generalizes from; "Add another anchor"
 * grows the set unboundedly (UI copy nudges toward 2-4, no hard cap) for trickier multi-segment
 * drift. This composable owns no `ChapterEditorViewModel` reference — [onApplyDrift]/[onCommitDrift]
 * are the VM's `applyDrift`/`commitDrift` passed in by the caller, mirroring
 * [com.calypsan.listenup.client.features.chaptereditor.components.ChapterDetailPanel]'s callback shape.
 *
 * @param draft The current chapter draft — supplies anchor candidates and each anchor's live title/source time.
 * @param playheadMs Deferred read of the current playback position (the screen's existing
 *   `PlayerScrubber`-idiom seam, shared with the detail panel's "Snap to playhead") — a Snap reads
 *   it once, at tap time.
 * @param onApplyDrift Dry-run preview; called after every Snap and every lock toggle.
 * @param onCommitDrift Applies the correction as one undo frame; called once, on Apply.
 * @param onGhostsChange Pushes the live ghost-preview markers up to the Timing lane (`null` clears
 *   them) — the sheet has no timeline of its own to render into.
 * @param onDismiss Closes the sheet without committing (Cancel, scrim tap, or system back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriftCorrectionSheet(
    draft: List<Chapter>,
    playheadMs: () -> Long,
    onApplyDrift: (anchors: List<ChapterAnchor>, lockedIds: Set<String>) -> DriftPreview,
    onCommitDrift: (anchors: List<ChapterAnchor>, lockedIds: Set<String>) -> Unit,
    onGhostsChange: (List<TimeMarker>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var slots by remember { mutableStateOf(draft.initialAnchorSlots()) }
    var lockedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<DriftPreview?>(null) }
    val chaptersById = remember(draft) { draft.associateBy { it.id } }

    fun revalidate() {
        val anchors = slots.toChapterAnchors()
        val result = if (anchors.isEmpty()) null else onApplyDrift(anchors, lockedIds)
        preview = result
        onGhostsChange((result as? DriftPreview.Ghosts)?.chapters?.toGhostMarkers())
    }

    fun closeSheet() {
        onGhostsChange(null)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = ::closeSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DriftSheetHeader()

            slots.forEachIndexed { index, slot ->
                val chapter = chaptersById[slot.chapterId] ?: return@forEachIndexed
                DriftAnchorRow(
                    chapter = chapter,
                    trueStartMs = slot.trueStartMs,
                    locked = chapter.id in lockedIds,
                    onSnap = {
                        slots = slots.toMutableList().apply { this[index] = slot.copy(trueStartMs = playheadMs()) }
                        revalidate()
                    },
                    onLockChange = { checked ->
                        lockedIds = if (checked) lockedIds + chapter.id else lockedIds - chapter.id
                        revalidate()
                    },
                )
            }

            AddAnchorControl(
                candidates = draft.filterNot { chapter -> slots.any { it.chapterId == chapter.id } },
                onAdd = { chapter -> slots = slots + AnchorSlot(chapterId = chapter.id) },
            )

            DriftRejectionMessage(reason = (preview as? DriftPreview.Rejected)?.reason)

            DriftSheetActions(
                canApply = preview is DriftPreview.Ghosts,
                onCancel = ::closeSheet,
                onApply = {
                    onCommitDrift(slots.toChapterAnchors(), lockedIds)
                    closeSheet()
                },
            )
        }
    }
}

@Composable
private fun DriftSheetHeader() {
    Text(text = stringResource(Res.string.chapter_editor_drift_title), style = MaterialTheme.typography.titleLarge)
    Text(
        text = stringResource(Res.string.chapter_editor_drift_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One anchor's card: chapter title, its snapped true-start (or a not-yet-snapped hint), Snap, and a lock toggle. */
@Composable
private fun DriftAnchorRow(
    chapter: Chapter,
    trueStartMs: Long?,
    locked: Boolean,
    onSnap: () -> Unit,
    onLockChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        trueStartMs?.let {
                            stringResource(
                                Res.string.chapter_editor_drift_anchor_snapped,
                                DurationFormatter.minutesSecondsClock(it.milliseconds),
                            )
                        } ?: stringResource(Res.string.chapter_editor_drift_anchor_unsnapped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSnap) {
                    Text(stringResource(Res.string.chapter_editor_drift_snap))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = locked, onCheckedChange = onLockChange)
                Text(stringResource(Res.string.chapter_editor_drift_locked), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "Add another anchor" — a dropdown of not-yet-anchored [candidates]; disabled once every chapter is anchored. */
@Composable
private fun AddAnchorControl(
    candidates: List<Chapter>,
    onAdd: (Chapter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = candidates.isNotEmpty()) {
            Text(stringResource(Res.string.chapter_editor_drift_add_anchor))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { chapter ->
                DropdownMenuItem(
                    text = { Text(chapter.title) },
                    onClick = {
                        onAdd(chapter)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Inline rejection copy for [DriftResult.Rejected]. [DriftResult.Rejected.InvertedAnchors] never
 * carries the offending pair, so this is a deliberately generic "these anchors are out of order"
 * message rather than a per-pair one.
 */
@Composable
private fun DriftRejectionMessage(reason: DriftResult.Rejected?) {
    if (reason == null) return
    val message =
        when (reason) {
            DriftResult.Rejected.InvertedAnchors -> stringResource(Res.string.chapter_editor_drift_inverted_warning)
            DriftResult.Rejected.BadAnchors -> stringResource(Res.string.chapter_editor_drift_bad_anchors_warning)
        }
    Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DriftSheetActions(
    canApply: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
        Button(onClick = onApply, enabled = canApply) { Text(stringResource(Res.string.chapter_editor_drift_apply)) }
    }
}

/** Seeds the sheet's opening anchor slots from [this] draft's first and last chapters (deduped when there's only one). */
private fun List<Chapter>.initialAnchorSlots(): List<AnchorSlot> =
    listOfNotNull(firstOrNull(), lastOrNull())
        .distinctBy { it.id }
        .map { AnchorSlot(chapterId = it.id) }

/** Only snapped slots become real anchors — an unsnapped slot is a placeholder, not yet a constraint. */
private fun List<AnchorSlot>.toChapterAnchors(): List<ChapterAnchor> =
    mapNotNull { slot -> slot.trueStartMs?.let { ChapterAnchor(chapterId = slot.chapterId, trueStartMs = it) } }

/** Maps a [DriftPreview.Ghosts] result onto the Timing lane's [TimeMarker] contract. */
private fun List<Chapter>.toGhostMarkers(): List<TimeMarker> =
    map { chapter ->
        TimeMarker(id = chapter.id, timeMs = chapter.startTime, label = chapter.title, styleKey = GHOST_STYLE_KEY)
    }
