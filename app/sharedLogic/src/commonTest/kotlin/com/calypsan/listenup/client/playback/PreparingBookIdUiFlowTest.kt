package com.calypsan.listenup.client.playback

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [preparingBookIdUiFlow] — the debounced, UI-facing view of a play-in-flight
 * signal (e.g. [PlaybackManager.preparingBookId]). Covers the #1's actual reported bug: tapping
 * play shows nothing for seconds. This flow is what fixes the *visible* half of that fix (the
 * other half — swallowing repeat taps — lives in [PlaybackManager.preparingBookId] itself and is
 * covered by `NowPlayingViewModelTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparingBookIdUiFlowTest :
    FunSpec({

        test("stays null when the source never goes non-null") {
            runTest {
                val source = MutableStateFlow<BookId?>(null)
                preparingBookIdUiFlow(source).test {
                    awaitItem() shouldBe null
                }
            }
        }

        test("does not surface the book id before the debounce window elapses — no flash on a fast prepare") {
            runTest {
                val source = MutableStateFlow<BookId?>(null)
                val bookId = BookId("book-1")
                preparingBookIdUiFlow(source).test {
                    awaitItem() shouldBe null

                    source.value = bookId
                    advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds - 1)
                    runCurrent()
                    expectNoEvents()

                    // Prepare resolves fast, well inside the window — never surfaced, so clearing
                    // back to null is a no-op re: emissions (distinctUntilChanged: still null).
                    source.value = null
                    runCurrent()
                    expectNoEvents()

                    advanceTimeBy(1_000)
                    runCurrent()
                    expectNoEvents()
                }
            }
        }

        test("surfaces the book id once it has been preparing longer than the debounce window") {
            runTest {
                val source = MutableStateFlow<BookId?>(null)
                val bookId = BookId("book-1")
                preparingBookIdUiFlow(source).test {
                    awaitItem() shouldBe null

                    source.value = bookId
                    advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds + 1)
                    runCurrent()
                    awaitItem() shouldBe bookId
                }
            }
        }

        test("clears immediately when the source clears, even mid-debounce") {
            runTest {
                val source = MutableStateFlow<BookId?>(null)
                val bookId = BookId("book-1")
                preparingBookIdUiFlow(source).test {
                    awaitItem() shouldBe null

                    source.value = bookId
                    advanceTimeBy(50)
                    runCurrent()
                    expectNoEvents() // still within the window, not surfaced yet

                    source.value = null // prepare finished before the window elapsed
                    runCurrent()
                    expectNoEvents() // was never surfaced, so there is nothing new to emit

                    advanceTimeBy(1_000)
                    runCurrent()
                    expectNoEvents() // the cancelled delayed-emit must never fire late
                }
            }
        }

        test("a fresh collector re-runs its own debounce window rather than inheriting an already-elapsed one") {
            runTest {
                // preparingBookIdUiFlow is a plain, COLD Flow (see PlaybackManager.preparingBookIdUi
                // KDoc) — there is deliberately no sharing/stateIn at this layer, per the rubric
                // ("state is .stateIn(scope) at the call site"). The accepted consequence: a fresh
                // collector has no memory of how long the source has actually been non-null, so it
                // re-runs its own PREPARING_UI_DELAY window from scratch rather than inheriting an
                // already-elapsed one. Bounded to at most one extra PREPARING_UI_DELAY of lag right
                // after a (re)subscribe — the same class of staleness WhileSubscribed/lifecycle-scoped
                // collection already introduces for every other field these surfaces observe.
                val source = MutableStateFlow<BookId?>(null)
                val bookId = BookId("book-1")
                source.value = bookId
                advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds + 1)
                runCurrent()
                // The source has already been non-null for well over the debounce window — before
                // anyone has ever subscribed to this (freshly constructed) flow.

                preparingBookIdUiFlow(source).test {
                    expectNoEvents() // still debouncing from THIS collector's own subscription point
                    advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds - 1)
                    runCurrent()
                    expectNoEvents()

                    advanceTimeBy(2)
                    runCurrent()
                    awaitItem() shouldBe bookId
                }
            }
        }

        test("clears immediately when the source clears AFTER being surfaced") {
            runTest {
                val source = MutableStateFlow<BookId?>(null)
                val bookId = BookId("book-1")
                preparingBookIdUiFlow(source).test {
                    awaitItem() shouldBe null

                    source.value = bookId
                    advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds + 1)
                    runCurrent()
                    awaitItem() shouldBe bookId

                    source.value = null
                    awaitItem() shouldBe null
                }
            }
        }
    })
