package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.api.dto.ChapterInput
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
internal fun List<Chapter>.retimed(
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
internal fun List<Chapter>.added(
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
internal fun List<Chapter>.removed(
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
internal fun List<Chapter>.retitled(
    chapterId: String,
    title: String,
): List<Chapter> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return this
    val index = indexOfFirst { it.id == chapterId }
    if (index < 0 || this[index].title == trimmed) return this
    return toMutableList().also { it[index] = it[index].copy(title = trimmed) }
}

// Markers are attributes of the chapter that opens the group, so these only touch those fields: a
// boundary never moves, and retime, drift and undo carry a marker along without knowing it exists.

/** Sets the Part marker [chapterId] carries. A name the wire would refuse is refused here. */
internal fun List<Chapter>.withPartMarker(
    chapterId: String,
    title: String,
): List<Chapter> = title.asMarkerName()?.let { name -> updated(chapterId) { it.copy(partTitle = name) } } ?: this

/** Sets the Book marker [chapterId] carries. A name the wire would refuse is refused here. */
internal fun List<Chapter>.withBookMarker(
    chapterId: String,
    title: String,
): List<Chapter> = title.asMarkerName()?.let { name -> updated(chapterId) { it.copy(bookTitle = name) } } ?: this

/** Clears [chapterId]'s Part marker. Its chapters join the group before them. */
internal fun List<Chapter>.withoutPartMarker(chapterId: String): List<Chapter> =
    updated(chapterId) { it.copy(partTitle = null) }

/** Clears [chapterId]'s Book marker. Its chapters join the group before them. */
internal fun List<Chapter>.withoutBookMarker(chapterId: String): List<Chapter> =
    updated(chapterId) { it.copy(bookTitle = null) }

/**
 * Moves the Part marker from [chapterId] to [toChapterId], keeping its name: this is "move where the
 * part begins", the honest form of dragging a chapter under one.
 *
 * Refused onto a chapter already opening a part, which would silently swallow that part.
 */
internal fun List<Chapter>.movedPartMarker(
    chapterId: String,
    toChapterId: String,
): List<Chapter> {
    val title = firstOrNull { it.id == chapterId }?.partTitle ?: return this
    if (moveTargets(ChapterMarkerTier.Part).none { it.id == toChapterId }) return this
    return withoutPartMarker(chapterId).withPartMarker(toChapterId, title)
}

/** Moves the Book marker from [chapterId] to [toChapterId]; see [movedPartMarker]. */
internal fun List<Chapter>.movedBookMarker(
    chapterId: String,
    toChapterId: String,
): List<Chapter> {
    val title = firstOrNull { it.id == chapterId }?.bookTitle ?: return this
    if (moveTargets(ChapterMarkerTier.Book).none { it.id == toChapterId }) return this
    return withoutBookMarker(chapterId).withBookMarker(toChapterId, title)
}

/** This text as a marker's name, or null when `ChapterInput` would throw on it at save. */
private fun String.asMarkerName(): String? =
    trim().takeIf { name -> name.isNotEmpty() && name.length <= ChapterInput.MAX_SECTION_TITLE }

/** [chapterId] replaced by [change]; the same list when there is no such chapter or nothing changed. */
private fun List<Chapter>.updated(
    chapterId: String,
    change: (Chapter) -> Chapter,
): List<Chapter> {
    val index = indexOfFirst { it.id == chapterId }
    if (index < 0) return this
    val next = change(this[index])
    return if (next == this[index]) this else toMutableList().also { it[index] = next }
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
