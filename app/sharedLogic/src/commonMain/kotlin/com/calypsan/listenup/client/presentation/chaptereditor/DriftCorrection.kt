package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.chapter.DriftResult
import com.calypsan.listenup.client.domain.chapter.correctDrift
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The guided drift flow's state: two pinned anchors and what they would do to the book.
 *
 * Drift is the editor's answer to *bulk* error — a scrape whose offset grows across the book, where
 * fixing 311 boundaries by hand is not a real option. The user pins two chapters they can hear are
 * right, and every other boundary is interpolated between them.
 *
 * Held as its own state rather than inside the editor's draft because it is a **proposal**: nothing
 * moves until it is applied, and abandoning it must leave the chapter set exactly as it was.
 *
 * @property first the earlier anchor, defaulting to the first chapter.
 * @property second the later anchor, defaulting to the last. Null while only one is pinned, which
 *   the spec treats as the degenerate case — a constant shift across the whole book.
 * @property lockedIds chapters exempt from correction; they keep their current start.
 */
data class DriftProposal(
    val first: ChapterAnchor,
    val second: ChapterAnchor? = null,
    val lockedIds: Set<String> = emptySet(),
) {
    /** The anchors in the order [correctDrift] expects. */
    val anchors: List<ChapterAnchor> get() = listOfNotNull(first, second)
}

/** What a [DriftProposal] would do, ready to preview or refuse. */
sealed interface DriftPreview {
    /**
     * The correction is valid and these are the resulting positions.
     *
     * @property corrected the whole set as it would become — drawn as ghost markers beside the
     *   current ones, never applied until the user says so.
     * @property affectedCount how many boundaries actually move, which is the number the summary
     *   quotes. Locked chapters are excluded, so it is not simply the chapter count.
     * @property firstOffsetMs how far the earliest anchor moves.
     * @property lastOffsetMs how far the latest moves. The pair is the drift, read as a spread.
     */
    data class Ready(
        val corrected: List<Chapter>,
        val affectedCount: Int,
        val firstOffsetMs: Long,
        val lastOffsetMs: Long,
    ) : DriftPreview {
        /** Total drift accumulated across the book — the headline number in the summary. */
        val spreadMs: Long get() = lastOffsetMs - firstOffsetMs
    }

    /**
     * The anchors cannot produce a correction.
     *
     * Kept as a typed reason rather than a disabled button with no explanation: a mis-set anchor is
     * the most likely mistake in this flow, and "Apply is greyed out" tells the user nothing about
     * which of the two they got wrong.
     */
    data class Refused(
        val reason: DriftRefusal,
    ) : DriftPreview
}

/** Why a drift proposal cannot be applied. */
enum class DriftRefusal {
    /** An anchor names a chapter that is not in the set, or both name the same one. */
    UnusableAnchors,

    /**
     * The pinned times run backwards relative to the scraped ones, which would reverse the book.
     *
     * Always a mis-set anchor rather than real drift — audio does not play in reverse — so the flow
     * blocks rather than "correcting" the book into nonsense.
     */
    InvertedAnchors,
}

/**
 * Works out what [proposal] would do, without changing anything.
 *
 * Pure, so the preview the user is shown and the set that gets applied are computed by the same
 * code from the same inputs — there is no second path that could disagree with the ghosts.
 */
fun previewDrift(
    chapters: List<Chapter>,
    proposal: DriftProposal,
    bookDurationMs: Long,
): DriftPreview =
    when (val result = correctDrift(chapters, proposal.anchors, bookDurationMs, proposal.lockedIds)) {
        is DriftResult.Corrected -> ready(chapters, result.chapters, proposal.lockedIds)
        DriftResult.Rejected.BadAnchors -> DriftPreview.Refused(DriftRefusal.UnusableAnchors)
        DriftResult.Rejected.InvertedAnchors -> DriftPreview.Refused(DriftRefusal.InvertedAnchors)
    }

private fun ready(
    before: List<Chapter>,
    after: List<Chapter>,
    lockedIds: Set<String>,
): DriftPreview.Ready {
    val startsBefore = before.associate { it.id to it.startTime }
    // Only boundaries that actually move are counted: a locked chapter, or one the map happens to
    // leave in place, has not been "shifted" and saying so would overstate what Apply does.
    val moved = after.filter { it.id !in lockedIds && startsBefore[it.id] != it.startTime }
    val offsets = after.mapNotNull { c -> startsBefore[c.id]?.let { c.startTime - it } }
    return DriftPreview.Ready(
        corrected = after,
        affectedCount = moved.size,
        firstOffsetMs = offsets.firstOrNull() ?: 0L,
        lastOffsetMs = offsets.lastOrNull() ?: 0L,
    )
}
