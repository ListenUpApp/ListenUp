package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.core.SeriesId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * [SeriesEditRepositoryImpl]'s merge-undo surface (#1061): listing the merges folded into a series,
 * and undoing one. Both are server-canonical — the receipt lives on the server, and the undo's
 * effects reach Room through the firehose like the merge's did.
 */
class SeriesEditRepositoryMergeUndoTest :
    FunSpec({
        fun repo(service: SeriesService): SeriesEditRepositoryImpl =
            SeriesEditRepositoryImpl(
                channel = RpcChannel.forTest(service),
                seriesDao = mock(MockMode.autofill),
                offlineEditor = noopOfflineEditor(),
            )

        val receipt =
            MergeReceipt(
                id = MergeReceiptId("r1"),
                sourceName = "The Stormlight Archive (dup)",
                mergedAt = 1_700_000_000_000L,
                mergedByName = "Simon",
                bookCount = 4,
            )

        test("the merges folded into a series come from the server") {
            runTest {
                val service = mock<SeriesService>()
                everySuspend { service.listMergeReceipts(SeriesId("s1")) } returns AppResult.Success(listOf(receipt))

                repo(service).listMergeReceipts(SeriesId("s1")) shouldBe AppResult.Success(listOf(receipt))
            }
        }

        test("undoing a merge returns what moved back") {
            runTest {
                val service = mock<SeriesService>()
                val undone = MergeUndoResult("s-old", booksRestored = 3, booksSkipped = 1, restoredAtTopLevel = false)
                everySuspend { service.undoSeriesMerge(MergeReceiptId("r1")) } returns AppResult.Success(undone)

                repo(service).undoMerge(MergeReceiptId("r1")) shouldBe AppResult.Success(undone)
            }
        }

        test("a refused undo arrives as its typed error, untouched") {
            runTest {
                val service = mock<SeriesService>()
                everySuspend { service.undoSeriesMerge(MergeReceiptId("r1")) } returns
                    AppResult.Failure(SeriesError.MergeAlreadyUndone())

                repo(service)
                    .undoMerge(MergeReceiptId("r1"))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<SeriesError.MergeAlreadyUndone>()
            }
        }
    })
