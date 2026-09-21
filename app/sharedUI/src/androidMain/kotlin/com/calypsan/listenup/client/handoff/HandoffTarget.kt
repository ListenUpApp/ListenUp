package com.calypsan.listenup.client.handoff

import com.calypsan.listenup.core.BookId

/**
 * What this device would hand to another one, if asked right now.
 *
 * Android's Continue On (API 37) asks a foreground activity what the user is in the middle of, so
 * the answer has to be computed from live state at the moment of the request. Keeping that decision
 * here rather than in `MainActivity` is deliberate: the platform call sites are untestable from this
 * repo's lanes — Robolectric does not emulate an API 37 OS feature — so the Activity keeps only the
 * glue and the judgement lives where a spec can reach it.
 */
sealed interface HandoffTarget {
    /** Continue this book on the other device. */
    data class Book(
        val bookId: BookId,
    ) : HandoffTarget

    /** Nothing worth continuing. */
    data object None : HandoffTarget
}

/**
 * Decides what to offer, given what is playing and what is on screen.
 *
 * ⛔ **Playing wins over viewed**, and that ordering is the whole judgement. Someone who is
 * listening and browsing at the same time is browsing *while* listening; handing over the page they
 * happen to be glancing at would abandon the thing actually in their ears.
 *
 * ⛔ **Nothing is a valid answer.** A handoff that lands the receiver on a generic library screen
 * is worse than no handoff offered: the system advertises "continue on your other device", the user
 * accepts, and arrives nowhere in particular. Offering [None] withdraws the affordance instead of
 * honouring it emptily.
 */
fun resolveHandoffTarget(
    playingBookId: BookId?,
    viewedBookId: BookId?,
): HandoffTarget {
    val bookId = playingBookId ?: viewedBookId ?: return HandoffTarget.None
    return HandoffTarget.Book(bookId)
}
