package com.calypsan.listenup.client.presentation.merge

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [MergeHistory] — the "Merged into this" list and its Undo (#1061), shared by the series editor
 * and the genre admin on every platform. A server with the receipts is faked in memory.
 */
class MergeHistoryTest :
    FunSpec({

        fun receipt(
            id: String,
            name: String,
            books: Int = 4,
        ) = MergeReceipt(
            id = MergeReceiptId(id),
            sourceName = name,
            mergedAt = 1_700_000_000_000L,
            mergedByName = "Simon",
            bookCount = books,
        )

        /** Receipts held server-side; an undo removes its receipt, as the real server's list does. */
        class FakeServer(
            var receipts: MutableList<MergeReceipt>,
        ) {
            var loadResult: AppResult<List<MergeReceipt>>? = null
            var undoResult: (MergeReceiptId) -> AppResult<MergeUndoResult> = { id ->
                receipts.removeAll { it.id == id }
                AppResult.Success(MergeUndoResult("src", booksRestored = 3, booksSkipped = 1, restoredAtTopLevel = false))
            }
            var undoGate: CompletableDeferred<Unit>? = null

            suspend fun load(): AppResult<List<MergeReceipt>> = loadResult ?: AppResult.Success(receipts.toList())

            suspend fun undo(id: MergeReceiptId): AppResult<MergeUndoResult> {
                undoGate?.await()
                return undoResult(id)
            }
        }

        fun TestScope.history(
            server: FakeServer,
            errorBus: ErrorBus = ErrorBus(),
        ) = MergeHistory(scope = backgroundScope, errorBus = errorBus, load = server::load, undo = server::undo)

        test("it lists the merges folded into this one") {
            runTest {
                val server = FakeServer(mutableListOf(receipt("r1", "Stormlight (dup)"), receipt("r2", "Stormlite")))
                val history = history(server)

                history.refresh()
                runCurrent()

                history.state.value
                    .shouldBeInstanceOf<MergeHistoryState.Ready>()
                    .receipts
                    .map { it.sourceName } shouldBe listOf("Stormlight (dup)", "Stormlite")
            }
        }

        test("a history that cannot be loaded says why, and can be tried again") {
            runTest {
                val server = FakeServer(mutableListOf(receipt("r1", "Stormlite")))
                server.loadResult = AppResult.Failure(TransportError.NetworkUnavailable())
                val history = history(server)

                history.refresh()
                runCurrent()
                history.state.value
                    .shouldBeInstanceOf<MergeHistoryState.Unavailable>()
                    .error
                    .shouldBeInstanceOf<TransportError.NetworkUnavailable>()

                server.loadResult = null
                history.refresh()
                runCurrent()
                history.state.value.shouldBeInstanceOf<MergeHistoryState.Ready>()
            }
        }

        test("an undo says what moved back, and the merge leaves the list") {
            runTest {
                val server = FakeServer(mutableListOf(receipt("r1", "Stormlite")))
                val history = history(server)
                history.refresh()
                runCurrent()

                history.undo(MergeReceiptId("r1"))
                runCurrent()

                val ready = history.state.value.shouldBeInstanceOf<MergeHistoryState.Ready>()
                ready.receipts.shouldBeEmpty()
                ready.undoingId.shouldBeNull()
                ready.outcome shouldBe
                    MergeUndoOutcome(sourceName = "Stormlite", booksRestored = 3, booksSkipped = 1, restoredAtTopLevel = false)
            }
        }

        test("while an undo runs, the row says so and a second press does nothing") {
            runTest {
                val server = FakeServer(mutableListOf(receipt("r1", "Stormlite")))
                val gate = CompletableDeferred<Unit>()
                server.undoGate = gate
                var undoCalls = 0
                val original = server.undoResult
                server.undoResult = { id ->
                    undoCalls++
                    original(id)
                }
                val history = history(server)
                history.refresh()
                runCurrent()

                history.undo(MergeReceiptId("r1"))
                runCurrent()
                history.state.value
                    .shouldBeInstanceOf<MergeHistoryState.Ready>()
                    .undoingId shouldBe MergeReceiptId("r1")
                history.undo(MergeReceiptId("r1"))
                gate.complete(Unit)
                runCurrent()

                undoCalls shouldBe 1
            }
        }

        test("a refused undo reaches the error bus, and the list is re-read from the server") {
            runTest {
                val server = FakeServer(mutableListOf(receipt("r1", "Stormlite")))
                server.undoResult = { id ->
                    // Another admin undid it first: the server no longer lists it.
                    server.receipts.removeAll { it.id == id }
                    AppResult.Failure(SeriesError.MergeAlreadyUndone())
                }
                val errorBus = ErrorBus()
                val seen = mutableListOf<AppError>()
                backgroundScope.launch { errorBus.errors.collect { seen += it } }
                val history = history(server, errorBus)
                history.refresh()
                runCurrent()

                history.undo(MergeReceiptId("r1"))
                runCurrent()

                seen.single().shouldBeInstanceOf<SeriesError.MergeAlreadyUndone>()
                val ready = history.state.value.shouldBeInstanceOf<MergeHistoryState.Ready>()
                ready.receipts.shouldBeEmpty()
                ready.outcome.shouldBeNull()
            }
        }
    })
