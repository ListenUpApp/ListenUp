package com.calypsan.listenup.client.presentation.home

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.LibraryShowcase
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.core.BookId
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The slot end to end over in-memory sources: §2.2 kinds, §2.1 latching, §2.3 contents (spec §4.1 TDD list). */
class HomeSlotPresenterTest :
    FunSpec({
        test("nothing is decided while the loaded book is restoring (no slot flash on a cold start)") {
            runTest {
                val rig = HomeSlotRig()
                rig.loaded.value = LoadedBook.Restoring
                rig.play("a", hoursAgo = 2)
                rig.continueListening.value = listOf(readyItem("a"))
                rig.slots().test {
                    awaitItem().shouldBeNull()
                    expectNoEvents()
                    rig.loaded.value = LoadedBook.NothingLoaded
                    awaitState<HomeSlot.Absent>().everythingInProgress.map { it.bookId } shouldBe listOf("a")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("nothing loaded and books in progress: no hero, the rail is every book in progress, live") {
            runTest {
                val rig = HomeSlotRig()
                rig.play("a", hoursAgo = 2)
                rig.continueListening.value = listOf(readyItem("a", positionMs = 1_000L), readyItem("b"))
                rig.slots().test {
                    val state = awaitState<HomeSlot.Absent>()
                    state.everythingInProgress.map { it.bookId } shouldBe listOf("a", "b")
                    rig.continueListening.value = listOf(readyItem("a"))
                    awaitState<HomeSlot.Absent>().everythingInProgress.map { it.bookId } shouldBe listOf("a")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a loaded book: Absent, and the rail lists the loaded book first") {
            runTest {
                val rig = HomeSlotRig()
                rig.play("a", hoursAgo = 1)
                rig.play("b", hoursAgo = 2)
                rig.loaded.value = LoadedBook.Loaded("a", isPlaying = true, isFinished = false)
                rig.continueListening.value = listOf(readyItem("a"), readyItem("b"))
                rig.slots().test {
                    val state = awaitState<HomeSlot.Absent>()
                    state.everythingInProgress.map { it.bookId } shouldBe listOf("a", "b")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("play/pause is not a decision point; closing the book is") {
            runTest {
                val rig = HomeSlotRig()
                // Listened before, nothing in progress: with the book closed, the slot is NothingInProgress.
                rig.play("old", hoursAgo = 30)
                rig.loaded.value = LoadedBook.Loaded("a", isPlaying = true, isFinished = false)
                val states = collectSlots(rig)
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.Absent>()
                val seen = states.size

                rig.loaded.value = LoadedBook.Loaded("a", isPlaying = false, isFinished = false)
                runCurrent()
                states.size shouldBe seen

                rig.loaded.value = LoadedBook.NothingLoaded
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.NothingInProgress>()
            }
        }

        test("JustFinished: the book, its dates, your rating, the next book and who else finished it") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 3)
                rig.books.setBooks(listOf(TestData.bookListItem(id = "done", title = "The Way of Kings", duration = 9_000L)))
                rig.nextByBook.value = mapOf("done" to nextBook("words"))
                rig.ratingsFlow.value = listOf(ListenerRating(bookId = "done", userId = "me", halfStars = 8, note = null, ratedAtMs = 1L))
                rig.finishers.value = listOf("Maya", "Tom", "Ana")
                rig.slots().test {
                    val card = awaitSlot<HomeSlot.JustFinished>().card
                    card.book.bookId shouldBe "done"
                    card.book.title shouldBe "The Way of Kings"
                    card.finishedAtMs shouldBe RIG_NOW - 3 * RIG_HOUR
                    card.startedAtMs shouldBe RIG_NOW - 13 * RIG_HOUR
                    card.yourHalfStars shouldBe 8
                    card.next shouldBe nextBook("words")
                    card.alsoFinished shouldBe AlsoFinished(names = listOf("Maya", "Tom"), othersCount = 1)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("rating from the card writes through, and the card shows it live") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 3)
                rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                val presenter = rig.presenter()

                presenter.rateFinished("done", halfStars = 6).shouldBeInstanceOf<AppResult.Success<Unit>>()

                verifySuspend { rig.ratings.rate("done", 6, null) }
                presenter.observe(rig.continueListening, rig.building, rig.runs, rig.dismissedHere).test {
                    awaitSlot<HomeSlot.JustFinished>().card.yourHalfStars.shouldBeNull()
                    rig.ratingsFlow.value = listOf(ListenerRating("done", "me", 6, null, 1L))
                    awaitSlot<HomeSlot.JustFinished> { it.card.yourHalfStars == 6 }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("dismissFinished writes a dismissal stamped now, and nothing else") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 3)

                rig.presenter().dismissFinished("done").shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row =
                    rig.positions
                        .observe(BookId("done"))
                        .first()
                        .shouldNotBeNull()
                row.finishedCardDismissedAtMs shouldBe RIG_NOW
                row.lastPlayedAtMs shouldBe RIG_NOW - 3 * RIG_HOUR
            }
        }

        test("a book finishing elsewhere waits for the next decision to take the slot") {
            runTest {
                val rig = HomeSlotRig()
                rig.play("a", hoursAgo = 5)
                rig.continueListening.value = listOf(readyItem("a"))
                rig.books.setBooks(listOf(TestData.bookListItem(id = "b")))
                val states = collectSlots(rig)
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.Absent>()

                rig.finish("b", hoursAgo = 1)
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.Absent>()

                rig.runs.value = 1
                runCurrent()
                states.slot
                    .shouldBeInstanceOf<HomeSlot.JustFinished>()
                    .card.book.bookId shouldBe "b"
            }
        }

        test("the 72-hour expiry waits for the next decision too") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 71)
                rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                val states = collectSlots(rig)
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.JustFinished>()

                rig.now += 2 * RIG_HOUR
                rig.play("older", hoursAgo = 200) // any position emission, now past the window; played before the finish
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.JustFinished>()

                rig.runs.value = 1
                runCurrent()
                states.slot.shouldBeInstanceOf<HomeSlot.NothingInProgress>()
            }
        }

        test("a dismissal made this visit retires the card at that decision") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 3)
                rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                rig.slots().test {
                    awaitSlot<HomeSlot.JustFinished>()
                    rig.dismissedHere.value = mapOf("done" to RIG_NOW - 3 * RIG_HOUR)
                    rig.runs.value = 1
                    awaitSlot<HomeSlot.NothingInProgress>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Play next book retires the card at the load, before any position says so (C10)") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("done", hoursAgo = 3)
                rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                rig.slots().test {
                    awaitSlot<HomeSlot.JustFinished>()
                    rig.loaded.value = LoadedBook.Loaded("next", isPlaying = true, isFinished = false)
                    awaitSlot<HomeSlot.Absent>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("first sync resolves in place when its book arrives: no slot, the book in the rail") {
            runTest {
                val rig = HomeSlotRig()
                rig.building.value = true
                rig.play("a", hoursAgo = 2)
                rig.continueListening.value =
                    listOf(
                        com.calypsan.listenup.client.domain.model.ContinueListeningItem
                            .Loading("a"),
                    )
                rig.slots().test {
                    awaitSlot<HomeSlot.FirstSync>()
                    rig.continueListening.value = listOf(readyItem("a"))
                    awaitState<HomeSlot.Absent>().everythingInProgress.map { it.bookId } shouldBe listOf("a")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a decision made while the leading book is still arriving resolves in place when it lands") {
            runTest {
                val rig = HomeSlotRig()
                rig.play("a", hoursAgo = 2)
                rig.continueListening.value =
                    listOf(
                        com.calypsan.listenup.client.domain.model.ContinueListeningItem
                            .Loading("a"),
                    )
                rig.slots().test {
                    awaitSlot<HomeSlot.NothingInProgress>()
                    rig.continueListening.value = listOf(readyItem("a"))
                    awaitSlot<HomeSlot.Absent>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("NothingInProgress offers the next unstarted book of the newest finished series") {
            runTest {
                val rig = HomeSlotRig()
                rig.finish("newer", hoursAgo = 100)
                rig.finish("older", hoursAgo = 200)
                rig.play("newer-2", hoursAgo = 150, positionMs = 500L) // started: skipped
                rig.nextByBook.value = mapOf("newer" to nextBook("newer-2"), "older" to nextBook("older-2"))
                rig.slots().test {
                    awaitSlot<HomeSlot.NothingInProgress>().candidate shouldBe nextBook("older-2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("NewListener shows the newest covers") {
            runTest {
                val rig = HomeSlotRig()
                val covers = listOf(ShelfCover("n1", "/c/n1.jpg", "h1"))
                rig.showcase.value = LibraryShowcase(covers = covers)
                rig.slots().test {
                    awaitSlot<HomeSlot.NewListener>().covers shouldBe covers
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/** The next state whose slot is a [T] (and passes [where]), skipping the transient states before it. */
private suspend inline fun <reified T : HomeSlot> ReceiveTurbine<HomeSlotState?>.awaitState(
    where: (T) -> Boolean = { true },
): HomeSlotState {
    while (true) {
        val state = awaitItem() ?: continue
        val slot = state.slot
        if (slot is T && where(slot)) return state
    }
}

private suspend inline fun <reified T : HomeSlot> ReceiveTurbine<HomeSlotState?>.awaitSlot(where: (T) -> Boolean = { true }): T =
    awaitState<T>(where).slot as T

/** Collects every slot state into a list, for tests that assert what did NOT change; read it after runCurrent(). */
private fun TestScope.collectSlots(rig: HomeSlotRig): List<HomeSlotState?> {
    val states = mutableListOf<HomeSlotState?>()
    backgroundScope.launch { rig.slots().collect { states += it } }
    return states
}

private val List<HomeSlotState?>.slot: HomeSlot?
    get() = lastOrNull()?.slot
