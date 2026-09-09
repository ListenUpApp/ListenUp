package com.calypsan.listenup.web.lifecycle

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.events.Event

/** How long a spec waits for a lifecycle handler that SHOULD have run. */
private const val EVENT_TIMEOUT_MS = 2_000L

/** Poll interval while waiting for a recorded call. */
private const val POLL_MS = 10L

private const val FIRST_POSITION_MS = 42_000L
private const val SECOND_POSITION_MS = 7_000L

private val FIRST_BOOK = BookId("book-1")
private val SECOND_BOOK = BookId("book-2")

/** Waits for the first entry to land, so no spec ever asserts against a race it has not lost yet. */
private suspend fun awaitFirstCall(calls: List<Any>) {
    withTimeout(EVENT_TIMEOUT_MS) { while (calls.isEmpty()) delay(POLL_MS) }
}

/**
 * The three browser lifecycle edges the web client now honours: the tab going away, the tab coming
 * back, and the network coming back.
 *
 * These drive the real `document`/`window` listeners with synthetic events — the only way to prove
 * the handler is *registered*, not merely callable. A spec that only invoked the function's own
 * body would still pass with `addEventListener` deleted.
 *
 * ⛔ Every registration is disposed in a `finally`. A leaked window listener answers a *later*
 * spec's synthetic events — the hazard `WebAppRootFixtures` documents for undisposed compositions.
 */
class TabLifecycleTest :
    FunSpec({

        test("hiding the tab flushes the playhead") {
            val flushes = mutableListOf<Pair<BookId, Long>>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                flushPositionWhenHidden(
                    playhead = { Playhead(FIRST_BOOK, FIRST_POSITION_MS) },
                    flush = { flushes += it.bookId to it.positionMs },
                    isHidden = { true },
                    scope = scope,
                )
            try {
                document.dispatchEvent(Event("visibilitychange"))

                awaitFirstCall(flushes)
                flushes.single() shouldBe (FIRST_BOOK to FIRST_POSITION_MS)
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a tab that became visible does not flush") {
            val flushes = mutableListOf<Pair<BookId, Long>>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                flushPositionWhenHidden(
                    playhead = { Playhead(FIRST_BOOK, FIRST_POSITION_MS) },
                    flush = { flushes += it.bookId to it.positionMs },
                    isHidden = { false },
                    scope = scope,
                )
            try {
                // A visibility edge that is not a hide, then a real hide. Waiting for the SECOND
                // event's flush is what proves the first produced nothing — a `delay` followed by
                // `shouldBeEmpty` would pass just as well against a handler that never ran at all.
                document.dispatchEvent(Event("visibilitychange"))
                window.dispatchEvent(Event("pagehide"))

                awaitFirstCall(flushes)
                flushes.size shouldBe 1
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("nothing is flushed when no book is loaded") {
            val flushes = mutableListOf<Pair<BookId, Long>>()
            var playhead: Playhead? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                flushPositionWhenHidden(
                    playhead = { playhead },
                    flush = { flushes += it.bookId to it.positionMs },
                    isHidden = { true },
                    scope = scope,
                )
            try {
                window.dispatchEvent(Event("pagehide"))
                playhead = Playhead(SECOND_BOOK, SECOND_POSITION_MS)
                window.dispatchEvent(Event("pagehide"))

                awaitFirstCall(flushes)
                flushes.single() shouldBe (SECOND_BOOK to SECOND_POSITION_MS)
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a disposed registration stops listening") {
            val disposed = mutableListOf<Pair<BookId, Long>>()
            val live = mutableListOf<Pair<BookId, Long>>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            flushPositionWhenHidden(
                playhead = { Playhead(FIRST_BOOK, FIRST_POSITION_MS) },
                flush = { disposed += it.bookId to it.positionMs },
                isHidden = { true },
                scope = scope,
            )()
            window.dispatchEvent(Event("pagehide"))

            val dispose =
                flushPositionWhenHidden(
                    playhead = { Playhead(SECOND_BOOK, SECOND_POSITION_MS) },
                    flush = { live += it.bookId to it.positionMs },
                    isHidden = { true },
                    scope = scope,
                )
            try {
                window.dispatchEvent(Event("pagehide"))

                // The live recorder's call is the arrival that makes the empty one meaningful.
                awaitFirstCall(live)
                disposed.shouldBeEmpty()
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a tab becoming visible recovers realtime sync") {
            val recoveries = mutableListOf<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                recoverSyncOnReturn(
                    recover = { recoveries += Unit },
                    isVisible = { true },
                    scope = scope,
                )
            try {
                document.dispatchEvent(Event("visibilitychange"))

                awaitFirstCall(recoveries)
                recoveries.size shouldBe 1
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("coming back online recovers realtime sync") {
            val recoveries = mutableListOf<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            // `isVisible` is false on purpose: an `online` event only ever means the network came
            // back, so the recover must not be gated on the visibility read at all.
            val dispose =
                recoverSyncOnReturn(
                    recover = { recoveries += Unit },
                    isVisible = { false },
                    scope = scope,
                )
            try {
                window.dispatchEvent(Event("online"))

                awaitFirstCall(recoveries)
                recoveries.size shouldBe 1
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a disposed registration stops recovering") {
            val disposed = mutableListOf<Unit>()
            val live = mutableListOf<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            recoverSyncOnReturn(
                recover = { disposed += Unit },
                isVisible = { true },
                scope = scope,
            )()
            window.dispatchEvent(Event("online"))

            val dispose =
                recoverSyncOnReturn(
                    recover = { live += Unit },
                    isVisible = { true },
                    scope = scope,
                )
            try {
                window.dispatchEvent(Event("online"))

                awaitFirstCall(live)
                disposed.shouldBeEmpty()
            } finally {
                dispose()
                scope.cancel()
            }
        }
    })
