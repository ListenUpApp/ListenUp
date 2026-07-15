package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.GenreMutation
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The delete-tombstone idempotency rule: a delete op re-fired after a provably-sent-but-lost response
 * hits an already-tombstoned row and the server returns its domain's NotFound. Because the desired end
 * state (gone) is already true, [orSuccessIfNotFound] folds every row-level target NotFound to Success
 * so the op drains cleanly instead of quarantining — while genuine failures still surface.
 */
class DeleteIdempotencyTest :
    FunSpec({
        test("orSuccessIfNotFound folds every row-level target NotFound to Success") {
            listOf(
                TagError.NotFound(),
                ShelfError.NotFound(),
                CollectionError.NotFound(),
                GenreError.NotFound(),
                SeriesError.NotFound(),
                ContributorError.NotFound(),
                SyncError.NotFound(domain = "entities", entityId = "e1"),
            ).forEach { notFound ->
                AppResult.Failure(notFound).orSuccessIfNotFound() shouldBe AppResult.Success(Unit)
            }
        }

        test("orSuccessIfNotFound leaves a non-NotFound failure untouched") {
            val failure = AppResult.Failure(TransportError.OutcomeUnknown())
            failure.orSuccessIfNotFound() shouldBe failure
        }

        test("orSuccessIfNotFound does not fold a sub-entity miss (BookNotFound) — that is a genuine failure") {
            val failure = AppResult.Failure(TagError.BookNotFound())
            failure.orSuccessIfNotFound() shouldBe failure
        }

        test("orSuccessIfNotFound leaves a Success untouched") {
            AppResult.Success(Unit).orSuccessIfNotFound() shouldBe AppResult.Success(Unit)
        }

        test("a delete sender whose RPC returns NotFound drains the op (not dead-lettered)") {
            runTest {
                val db = createInMemoryTestDatabase()
                // The sender models a re-fired delete that hits an already-tombstoned row: the RPC
                // answers NotFound, which orSuccessIfNotFound folds to Success so the op drains.
                val sender =
                    DomainPendingOperationSender(
                        mapOf(
                            OutboxChannels.Genres.name to
                                OutboxOpSender(OutboxChannels.Genres) { _, _ ->
                                    AppResult.Failure(GenreError.NotFound()).orSuccessIfNotFound()
                                },
                        ),
                    )
                val queue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = sender)
                val opId =
                    queue.enqueue(
                        channel = OutboxChannels.Genres,
                        entityId = "g1",
                        op = OpKind.Delete,
                        payload = contractJson.encodeToString(OutboxChannels.Genres.serializer, GenreMutation.Delete),
                        ownerUserId = "u1",
                    )

                queue.drain()

                // A successful send deletes the op — nothing dead-lettered, nothing left to dispatch.
                db.pendingOperationV2Dao().get(opId).shouldBeNull()
                db.close()
            }
        }
    })
