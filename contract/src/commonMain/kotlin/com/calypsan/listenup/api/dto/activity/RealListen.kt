package com.calypsan.listenup.api.dto.activity

/**
 * What counts as really listening: one definition, read by the client's Activity feed, by Discover's
 * "what others are listening to" and by the server's "started a book" signal, so "worth telling people
 * about" means the same thing everywhere.
 *
 * Below [THRESHOLD_MS], listening is still real for stats and progress; it just isn't news. An
 * eight-second tap on a book is not something anyone should be told about.
 */
object RealListen {
    /** Listening shorter than this, in one sitting or one listen-through, is not announced. */
    const val THRESHOLD_MS: Long = 60_000L

    /**
     * Longest idle stretch that still counts as the same sitting. Pausing to make coffee or take a
     * call does not end an evening with a book; picking it up again after lunch is a separate act.
     */
    const val SITTING_GAP_MS: Long = 60L * 60_000L
}

/**
 * One closed stretch of someone listening to a book, as recorded: one playback span, from play to pause.
 *
 * @property userId who listened.
 * @property bookId what they listened to.
 * @property startedAtMs when the span began, epoch milliseconds.
 * @property endedAtMs when it ended, epoch milliseconds.
 */
data class ListenSpan(
    val userId: String,
    val bookId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
) {
    /** How long the span lasted; never negative, whatever the clocks said. */
    val listenedMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
}

/**
 * One person's unbroken time with one book: every span of theirs on that book with idle gaps of at most
 * [RealListen.SITTING_GAP_MS] between them. An evening paused for the kettle is one sitting, not three.
 *
 * @property parts the caller's items this sitting was made from, oldest first.
 * @property userId who listened.
 * @property bookId what they listened to.
 * @property startedAtMs when the first span began.
 * @property endedAtMs when the last span ended.
 * @property listenedMs the spans' lengths added up; idle time between them is not listening.
 */
data class Sitting<T>(
    val parts: List<T>,
    val userId: String,
    val bookId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val listenedMs: Long,
) {
    /** Long enough to tell people about: at least [RealListen.THRESHOLD_MS] of listening. */
    val isRealListen: Boolean get() = listenedMs >= RealListen.THRESHOLD_MS
}

/**
 * Groups listening into sittings, newest first, by [RealListen.SITTING_GAP_MS]. [spanOf] reads each item's
 * span. The one place that decides what a sitting is: the Activity feed and Discover both call it.
 *
 * Spans join by person and book, not by position in a list: someone else listening at the same time never
 * splits a sitting in two. Overlapping spans (two devices at once) join the same sitting.
 */
fun <T> List<T>.groupIntoSittings(spanOf: (T) -> ListenSpan): List<Sitting<T>> =
    map { it to spanOf(it) }
        .groupBy { (_, span) -> span.userId to span.bookId }
        .values
        .flatMap { listens -> listens.sortedBy { (_, span) -> span.startedAtMs }.splitAtGaps() }
        .map { listens -> listens.toSitting() }
        .sortedByDescending { it.endedAtMs }

/** Splits one person's time with one book, oldest first, wherever the idle gap exceeds [RealListen.SITTING_GAP_MS]. */
private fun <T> List<Pair<T, ListenSpan>>.splitAtGaps(): List<List<Pair<T, ListenSpan>>> =
    fold(mutableListOf<MutableList<Pair<T, ListenSpan>>>()) { sittings, listen ->
        val current = sittings.lastOrNull()
        val lastEnd = current?.maxOf { (_, span) -> span.endedAtMs }
        if (current != null && lastEnd != null && listen.second.startedAtMs - lastEnd <= RealListen.SITTING_GAP_MS) {
            current += listen
        } else {
            sittings += mutableListOf(listen)
        }
        sittings
    }

private fun <T> List<Pair<T, ListenSpan>>.toSitting(): Sitting<T> {
    val spans = map { (_, span) -> span }
    return Sitting(
        parts = map { (item, _) -> item },
        userId = spans.first().userId,
        bookId = spans.first().bookId,
        startedAtMs = spans.minOf { it.startedAtMs },
        endedAtMs = spans.maxOf { it.endedAtMs },
        listenedMs = spans.sumOf { it.listenedMs },
    )
}
