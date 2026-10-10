package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What the launcher's Search and Sleep-timer shortcuts do once they reach the navigation layer.
 *
 * The labels promise an open search field and a running timer; the handler used to deliver a bare
 * Library tab and a book that never stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutActionHandlingTest :
    FunSpec({

        test("Search selects the Library and asks the shell to open search once") {
            runTest {
                val harness = Harness(recentBooks = emptyList())

                harness.handle(ShortcutAction.Search)

                harness.searchRequests shouldBe 1
                harness.selectedDestinations shouldBe listOf(ShellDestination.Library)
            }
        }

        test("SleepTimer with a book already active sets the default timer without restarting playback") {
            runTest {
                val harness = Harness(recentBooks = listOf(book("b1")), activeBookId = "b0")

                harness.handle(ShortcutAction.SleepTimer(timerMinutes = null))

                harness.playback.events shouldBe listOf("timer:Duration(minutes=30)", "expand")
            }
        }

        test("SleepTimer with nothing loaded plays the latest book and sets the timer only once it is current") {
            runTest {
                val harness = Harness(recentBooks = listOf(book("b1")))

                val handling = launch { harness.handle(ShortcutAction.SleepTimer(timerMinutes = 45)) }
                runCurrent()

                // playBook has been asked for, but the book is not current yet: a timer set now
                // would be cancelled by the book change.
                harness.playback.events shouldBe listOf("play:b1", "expand")

                harness.playback.activeBookIdFlow.value = "b1"
                runCurrent()
                handling.join()

                harness.playback.events shouldBe listOf("play:b1", "expand", "timer:Duration(minutes=45)")
            }
        }

        test("SleepTimer whose book never becomes current sets no timer") {
            runTest {
                val harness = Harness(recentBooks = listOf(book("b1")))

                val handling = launch { harness.handle(ShortcutAction.SleepTimer(timerMinutes = null)) }
                runCurrent()
                advanceTimeBy(10_001)
                runCurrent()
                handling.join()

                harness.playback.events shouldBe listOf("play:b1", "expand")
            }
        }

        test("SleepTimer with no recent book sets no timer and lands on the Library") {
            runTest {
                val harness = Harness(recentBooks = emptyList())

                harness.handle(ShortcutAction.SleepTimer(timerMinutes = null))

                harness.playback.events shouldBe emptyList()
                harness.selectedDestinations shouldBe listOf(ShellDestination.Library)
            }
        }
    })

private class Harness(
    recentBooks: List<ContinueListeningBook>,
    activeBookId: String? = null,
) {
    val playback = RecordingPlayback(activeBookId)
    val selectedDestinations = mutableListOf<ShellDestination>()
    var searchRequests = 0
    private val backStack = NavBackStack<NavKey>(Shell)
    private val home = FakeHome(recentBooks)

    suspend fun handle(action: ShortcutAction) {
        handleShortcutAction(
            action = action,
            homeRepository = home,
            playback = playback,
            backStack = backStack,
            onSelectShellDestination = { selectedDestinations += it },
            onOpenSearch = { searchRequests++ },
        )
    }
}

private class RecordingPlayback(
    activeBookId: String?,
) : ShortcutPlayback {
    val activeBookIdFlow = MutableStateFlow(activeBookId)
    val events = mutableListOf<String>()

    override val activeBookId: String? get() = activeBookIdFlow.value
    override val activeBookIds: Flow<String?> = activeBookIdFlow

    override fun playBook(bookId: BookId) {
        events += "play:${bookId.value}"
    }

    override fun expand() {
        events += "expand"
    }

    override fun setSleepTimer(mode: SleepTimerMode) {
        events += "timer:$mode"
    }
}

private class FakeHome(
    private val books: List<ContinueListeningBook>,
) : HomeRepository {
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> =
        if (books.isEmpty()) AppResult.Failure(InternalError()) else AppResult.Success(books.take(limit))

    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = flowOf(emptyList())
}

private fun book(id: String) =
    ContinueListeningBook(
        bookId = id,
        title = "Book $id",
        authorNames = "An Author",
        coverPath = null,
        progress = 0.5f,
        currentPositionMs = 3_600_000L,
        totalDurationMs = 7_200_000L,
        lastPlayedAt = "2024-01-01T12:00:00Z",
    )
