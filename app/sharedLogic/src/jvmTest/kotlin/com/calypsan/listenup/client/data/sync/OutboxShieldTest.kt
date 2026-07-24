package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The anti-flicker in-flight shield, proven against the REAL [PendingOperationQueue] and the real
 * `books` composed handler wired exactly as production wires it — the outbox is consulted via
 * [PendingOperationQueue.hasQueuedOpFor]. Distinct from the fake-query coverage in
 * `ComposedSyncDomainHandlerTest`/`BooksDomainTest`: here the shield state comes from actually
 * enqueuing (and draining) ops, so the entity-level keying is exercised end to end.
 *
 * Before the fix these scenarios full-applied every echo and visibly reverted the optimistic edit —
 * the `clientOpId`-based echo dedup was dead on the wire because no write path ever set a `clientOpId`.
 */
class OutboxShieldTest :
    FunSpec({

        test("a stale SSE echo is shielded while a local edit is queued, then applies once the op drains") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.pendingOperationV2Dao()
                    val queue = PendingOperationQueue(dao = dao, sender = PendingOperationSender { AppResult.Success(Unit) })
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(
                                RoomTransactionRunner(db),
                                ClientSyncDomainRegistry(),
                                OutboxInFlightQuery(queue::hasQueuedOpFor),
                            )

                    // The user's optimistic local edit is on screen (title "User Edit", rev 1).
                    handler.onEvent(created(bookPayload(id = "b1", title = "User Edit", revision = 1)))

                    // …and its outbox op is in flight.
                    val opId = queue.enqueue(OutboxChannels.Books, "b1", OpKind.Update, "{}", "u1")

                    // A stale echo (the server's pre-edit state) arrives — it MUST be shielded.
                    handler.onEvent(updatedEvent(bookPayload(id = "b1", title = "Stale Server State", revision = 2)))
                    db.bookDao().getById(BookId("b1"))!!.title shouldBe "User Edit"

                    // The op drains successfully → removed from the queue → the shield lifts.
                    dao.delete(opId)
                    handler.onEvent(updatedEvent(bookPayload(id = "b1", title = "Converged", revision = 3)))
                    db.bookDao().getById(BookId("b1"))!!.title shouldBe "Converged"
                } finally {
                    db.close()
                }
            }
        }

        test("a catch-up snapshot is shielded while a local edit is queued") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.pendingOperationV2Dao()
                    val queue = PendingOperationQueue(dao = dao, sender = PendingOperationSender { AppResult.Success(Unit) })
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(
                                RoomTransactionRunner(db),
                                ClientSyncDomainRegistry(),
                                OutboxInFlightQuery(queue::hasQueuedOpFor),
                            )

                    handler.onEvent(created(bookPayload(id = "b1", title = "User Edit", revision = 1)))
                    queue.enqueue(OutboxChannels.Books, "b1", OpKind.Update, "{}", "u1")

                    // A REST catch-up page carrying stale state must be shielded too.
                    handler.onCatchUpItem(bookPayload(id = "b1", title = "Stale Server State", revision = 2), isTombstone = false)
                    db.bookDao().getById(BookId("b1"))!!.title shouldBe "User Edit"
                } finally {
                    db.close()
                }
            }
        }

        test("B2: two rapid edits — the first echo does not revert the second edit's optimistic state") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.pendingOperationV2Dao()
                    val queue = PendingOperationQueue(dao = dao, sender = PendingOperationSender { AppResult.Success(Unit) })
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(
                                RoomTransactionRunner(db),
                                ClientSyncDomainRegistry(),
                                OutboxInFlightQuery(queue::hasQueuedOpFor),
                            )

                    // Two rapid edits (books does not coalesce): the latest optimistic state is "Edit 2".
                    handler.onEvent(created(bookPayload(id = "b1", title = "Edit 2", revision = 1)))
                    val op1 = queue.enqueue(OutboxChannels.Books, "b1", OpKind.Update, "{}", "u1")
                    queue.enqueue(OutboxChannels.Books, "b1", OpKind.Update, "{}", "u1") // op2 stays queued

                    // op1 drains first; its echo reflects EDIT 1 only (server hasn't seen edit 2 yet).
                    dao.delete(op1)
                    handler.onEvent(updatedEvent(bookPayload(id = "b1", title = "Edit 1", revision = 2)))

                    // Because op2 is still in flight, echo1 must NOT revert the optimistic "Edit 2".
                    db.bookDao().getById(BookId("b1"))!!.title shouldBe "Edit 2"
                } finally {
                    db.close()
                }
            }
        }
    })

private fun created(payload: BookSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        payload = payload,
    )

private fun updatedEvent(payload: BookSyncPayload) =
    SyncEvent.Updated(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        payload = payload,
    )

private fun bookPayload(
    id: String,
    title: String,
    revision: Long,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = emptyList<BookContributorPayload>(),
        series = emptyList<BookSeriesPayload>(),
        audioFiles = emptyList<BookAudioFilePayload>(),
        chapters = emptyList<BookChapterPayload>(),
        revision = revision,
        updatedAt = revision * 100L,
        createdAt = 1L,
        deletedAt = null,
    )
