package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.domain.model.Chapter

/**
 * A way a chapter set is not fit to save.
 *
 * Typed rather than a message string: the editor can point at the offending row, and the reasons
 * stay checkable in tests instead of being matched on prose.
 */
sealed interface ChapterSetProblem {
    /** The chapter this problem is about, for selecting the row that needs attention. */
    val chapterId: String

    /** A boundary with no title is unnavigable — the one thing a chapter exists to provide. */
    data class BlankTitle(
        override val chapterId: String,
    ) : ChapterSetProblem

    /** Past what the server will accept, so it would be refused on arrival. */
    data class TitleTooLong(
        override val chapterId: String,
        val length: Int,
    ) : ChapterSetProblem

    /** Two boundaries at the same instant, or one that sits before its predecessor. */
    data class NotStrictlyIncreasing(
        override val chapterId: String,
    ) : ChapterSetProblem

    /** A boundary before the start of the audio, or at or past its end. */
    data class OutsideBook(
        override val chapterId: String,
        val startMs: Long,
    ) : ChapterSetProblem
}

/**
 * Checks a whole chapter set before it is allowed anywhere near the wire.
 *
 * The individual edit operations already keep the set valid — they clamp, refuse, and re-derive.
 * This exists because *they are not the only way the set changes*: drift correction replaces all
 * 311 boundaries in one move, and an editor that trusted its own operations would have no answer
 * when that produced something impossible.
 *
 * It is also the difference between a refusal and a crash. [ChapterInput] validates in its `init`
 * block, so mapping an invalid set onto the wire types **throws** — inside a save coroutine, where
 * the user sees a vanished save rather than a reason. Checking first turns that into a typed
 * problem pointing at the row responsible.
 *
 * An empty set is valid: a book with no chapters is the editor's empty state, not an error.
 *
 * @param bookDurationMs the book's own duration. Boundaries at or past this address no audio.
 * @return every problem found, in chapter order. Empty means the set is safe to send.
 */
internal fun List<Chapter>.validateForSave(bookDurationMs: Long): List<ChapterSetProblem> {
    val problems = mutableListOf<ChapterSetProblem>()
    forEachIndexed { index, chapter ->
        if (chapter.title.isBlank()) {
            problems += ChapterSetProblem.BlankTitle(chapter.id)
        } else if (chapter.title.length > ChapterInput.MAX_TITLE) {
            problems += ChapterSetProblem.TitleTooLong(chapter.id, chapter.title.length)
        }
        if (chapter.startTime < 0L || chapter.startTime >= bookDurationMs) {
            problems += ChapterSetProblem.OutsideBook(chapter.id, chapter.startTime)
        }
        // Compared against the previous entry rather than by sorting, because the ORDER as held is
        // what will be sent. A set that only sorts into validity is still wrong.
        if (index > 0 && chapter.startTime <= this[index - 1].startTime) {
            problems += ChapterSetProblem.NotStrictlyIncreasing(chapter.id)
        }
    }
    return problems
}
