package com.calypsan.listenup.web

import com.calypsan.listenup.domain.BookProgress

private const val FIXTURE_BOOK_MS = 1_000_000L

/**
 * A book [fraction] of the way through, as the one rule builds it: fixtures go through [BookProgress.of] like the
 * code they stand in for, so their percent and time left are never a second rule.
 */
internal fun inProgressAt(fraction: Float): BookProgress.InProgress =
    BookProgress.of(
        (FIXTURE_BOOK_MS * fraction).toLong(),
        FIXTURE_BOOK_MS,
        finishedFlag = false,
    ) as BookProgress.InProgress
