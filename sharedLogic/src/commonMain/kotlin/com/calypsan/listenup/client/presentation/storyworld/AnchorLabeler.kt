package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.Chapter
import kotlin.time.Duration.Companion.milliseconds

/**
 * A human-facing description of where a Story World anchor sits in a book — pure data,
 * no localized strings. The UI (not this layer) maps each variant to a localized string
 * resource; this type only carries the facts needed to pick and fill that resource.
 *
 * Public (unlike [AnchorLabeler], which stays internal): sharedUI consumes this shape directly
 * off [com.calypsan.listenup.client.presentation.storyworld.EventRow.anchor] to render each Story
 * World log entry's anchor caption.
 */
sealed interface AnchorLabel {
    /** The entry has no book anchor at all — it's always safe to show. */
    data object AlwaysVisible : AnchorLabel

    /** The entry is anchored to a book, but not to any position within it. */
    data class BookOnly(
        val bookLabel: String,
    ) : AnchorLabel

    /** The entry is anchored to the very start of the book (positionMs == 0). */
    data class Beginning(
        val bookLabel: String,
    ) : AnchorLabel

    /** The entry is anchored inside a known chapter of the book. */
    data class AtChapter(
        val bookLabel: String,
        val chapterTitle: String,
    ) : AnchorLabel

    /** The entry is anchored to an elapsed time that doesn't map to a known chapter. */
    data class AtTime(
        val bookLabel: String,
        val formattedTime: String,
    ) : AnchorLabel
}

/**
 * Resolves a Story World anchor `(bookLabel, chapters, positionMs)` into an [AnchorLabel]
 * describing where in the book that anchor sits.
 *
 * Precedence, in order:
 * 1. `bookLabel == null` → [AnchorLabel.AlwaysVisible].
 * 2. `positionMs == null` → [AnchorLabel.BookOnly].
 * 3. `positionMs == 0L` → [AnchorLabel.Beginning].
 * 4. The last chapter whose `startTime <= positionMs`, provided `positionMs` still falls
 *    within that chapter's span (`positionMs < startTime + duration`) → [AnchorLabel.AtChapter].
 * 5. Otherwise (no containing chapter — e.g. past the last chapter's end, or no chapters
 *    at all) → [AnchorLabel.AtTime], formatted via [DurationFormatter.hoursMinutes].
 */
internal object AnchorLabeler {
    fun label(
        bookLabel: String?,
        chapters: List<Chapter>,
        positionMs: Long?,
    ): AnchorLabel {
        if (bookLabel == null) return AnchorLabel.AlwaysVisible
        if (positionMs == null) return AnchorLabel.BookOnly(bookLabel)
        if (positionMs == 0L) return AnchorLabel.Beginning(bookLabel)

        val containingChapter =
            chapters
                .lastOrNull { it.startTime <= positionMs }
                ?.takeIf { positionMs < it.startTime + it.duration }

        return if (containingChapter != null) {
            AnchorLabel.AtChapter(bookLabel, containingChapter.title)
        } else {
            AnchorLabel.AtTime(bookLabel, DurationFormatter.hoursMinutes(positionMs.milliseconds))
        }
    }
}
