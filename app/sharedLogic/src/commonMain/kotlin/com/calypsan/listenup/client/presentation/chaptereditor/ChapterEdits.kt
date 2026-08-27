package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter

/** Smallest gap the editor will leave between two boundaries, so a chapter is never zero-length. */
private const val MIN_GAP_MS = 1L

// The four edits the spec reduces every chapter operation to: add, remove, retitle, retime.
//
// Pure list-to-list transforms, deliberately: every one has an invariant far easier to state as
// arithmetic than to verify by dragging something. Chapters are contiguous and ordered by start
// time, so *order is never edited directly* — retiming re-sorts, and that is the whole of
// "reordering" in this editor.
//
// Each returns the receiver unchanged when the edit cannot apply, which the draft reads as "no
// edit happened" and declines to push an undo frame for.

/**
 * Moves [chapterId] to [newStartMs], clamped to the open interval between its neighbours.
 *
 * Clamping rather than rejecting is what makes dragging feel right: a boundary pushed against its
 * neighbour stops there instead of snapping back or, worse, jumping past it and silently
 * reordering the book. The last chapter is additionally bounded by [bookDurationMs], since a
 * boundary past the end of the audio addresses nothing.
 */
fun List<Chapter>.retimed(
    chapterId: String,
    newStartMs: Long,
    bookDurationMs: Long,
): List<Chapter> {
    val index = indexOfFirst { it.id == chapterId }
    if (index < 0) return this

    val lowerBound = if (index == 0) 0L else this[index - 1].startTime + MIN_GAP_MS
    val upperBound =
        if (index == lastIndex) {
            bookDurationMs - MIN_GAP_MS
        } else {
            this[index + 1].startTime - MIN_GAP_MS
        }
    if (upperBound < lowerBound) return this

    val clamped = newStartMs.coerceIn(lowerBound, upperBound)
    if (clamped == this[index].startTime) return this
    return toMutableList()
        .also { it[index] = it[index].copy(startTime = clamped) }
        .withDerivedDurations(bookDurationMs)
}

/**
 * Inserts a boundary at [atMs], splitting the chapter that contains it.
 *
 * Refused when it would land on an existing boundary — two chapters starting at the same
 * millisecond is not a set the rest of the editor can reason about, and the user gets nothing
 * useful from a zero-length chapter.
 */
fun List<Chapter>.added(
    id: String,
    title: String,
    atMs: Long,
    bookDurationMs: Long,
): List<Chapter> {
    if (atMs < 0L || atMs >= bookDurationMs) return this
    if (any { it.startTime == atMs }) return this
    val inserted = Chapter(id = id, title = title, duration = 0L, startTime = atMs)
    return (this + inserted).sortedBy { it.startTime }.withDerivedDurations(bookDurationMs)
}

/**
 * Removes [chapterId], merging its span into the chapter before it.
 *
 * Removing the *first* chapter instead extends the new first chapter back to the start, because a
 * book cannot begin partway through — every millisecond of audio belongs to some chapter.
 * Refused on the last remaining chapter: a book with no chapters at all is the empty state, which
 * is a different screen and a deliberate act, not the tail of a delete.
 */
fun List<Chapter>.removed(
    chapterId: String,
    bookDurationMs: Long,
): List<Chapter> {
    if (size <= 1) return this
    val index = indexOfFirst { it.id == chapterId }
    if (index < 0) return this

    val remaining = filterIndexed { i, _ -> i != index }.toMutableList()
    if (index == 0) remaining[0] = remaining[0].copy(startTime = 0L)
    return remaining.withDerivedDurations(bookDurationMs)
}

/** Retitles [chapterId]. A blank title is refused — an untitled boundary is unnavigable. */
fun List<Chapter>.retitled(
    chapterId: String,
    title: String,
): List<Chapter> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return this
    val index = indexOfFirst { it.id == chapterId }
    if (index < 0 || this[index].title == trimmed) return this
    return toMutableList().also { it[index] = it[index].copy(title = trimmed) }
}

/**
 * Re-derives every duration from contiguity: a chapter runs until the next one starts, and the
 * last runs to the end of the book.
 *
 * Start time is the only stored boundary; duration is a consequence. Recomputing it after every
 * edit is what keeps the two from ever contradicting each other — the invariant the spec pins as
 * un-violable.
 */
internal fun List<Chapter>.withDerivedDurations(bookDurationMs: Long): List<Chapter> =
    mapIndexed { i, chapter ->
        val end = if (i == lastIndex) bookDurationMs else this[i + 1].startTime
        chapter.copy(duration = (end - chapter.startTime).coerceAtLeast(0L))
    }
