package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.core.MergeReceiptId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the merge-undo wire shapes: both DTOs survive a round trip, and each new error keeps a
 * stable discriminator, a constant message, and `isRetryable = false` (every one of them needs the
 * user to act — undo a later merge, rename a genre — before a retry could succeed).
 */
class MergeUndoContractTest :
    FunSpec({

        test("MergeReceipt round-trips, including a gone merger") {
            val receipt =
                MergeReceipt(
                    id = MergeReceiptId("r1"),
                    sourceName = "The Dark Tower (Bachman)",
                    mergedAt = 1_730_000_000_000L,
                    mergedByName = null,
                    bookCount = 7,
                )
            val json = contractJson.encodeToString(MergeReceipt.serializer(), receipt)
            contractJson.decodeFromString(MergeReceipt.serializer(), json) shouldBe receipt
        }

        test("MergeUndoResult round-trips") {
            val result =
                MergeUndoResult(restoredSourceId = "s1", booksRestored = 10, booksSkipped = 2, restoredAtTopLevel = true)
            val json = contractJson.encodeToString(MergeUndoResult.serializer(), result)
            contractJson.decodeFromString(MergeUndoResult.serializer(), json) shouldBe result
        }

        val newErrors: List<Pair<String, AppError>> =
            listOf(
                "SeriesError.MergeReceiptNotFound" to SeriesError.MergeReceiptNotFound(debugInfo = "x"),
                "SeriesError.MergeAlreadyUndone" to SeriesError.MergeAlreadyUndone(debugInfo = "x"),
                "SeriesError.MergeTargetGone" to SeriesError.MergeTargetGone(debugInfo = "x"),
                "GenreError.MergeReceiptNotFound" to GenreError.MergeReceiptNotFound(debugInfo = "x"),
                "GenreError.MergeAlreadyUndone" to GenreError.MergeAlreadyUndone(debugInfo = "x"),
                "GenreError.MergeTargetGone" to GenreError.MergeTargetGone(debugInfo = "x"),
                "GenreError.MergeSourceNameTaken" to GenreError.MergeSourceNameTaken(debugInfo = "x"),
            )

        newErrors.forEach { (discriminator, error) ->
            test("$discriminator round-trips through AppError with a stable discriminator") {
                val json = contractJson.encodeToString(AppError.serializer(), error)
                json.contains("\"$discriminator\"") shouldBe true
                contractJson.decodeFromString(AppError.serializer(), json) shouldBe error
            }

            test("$discriminator has a constant message and is not retryable") {
                error.isRetryable shouldBe false
                error.message.endsWith(".") shouldBe true
            }
        }
    })
