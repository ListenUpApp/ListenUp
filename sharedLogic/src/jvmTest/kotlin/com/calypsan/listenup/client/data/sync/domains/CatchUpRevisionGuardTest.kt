package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The reproduction file for the catch-up/live-frame race (plan 079): a from-zero
 * catch-up page is a snapshot at read time but applies whenever its transaction
 * commits, which can be after a fresher live SSE frame for the same entity already
 * committed. Modeled on `BookMoodsDomainTest.kt` — a real in-memory Room database
 * and the production `tagsDomain` handler, no fakes.
 *
 * The two tests below express the racing interleaving directly: `onEvent` and
 * `onCatchUpItem` are plain suspend calls, so "the page commits after the live
 * frame" is simply calling them in that order.
 */
class CatchUpRevisionGuardTest :
    FunSpec({

        test("stale catch-up upsert after a fresher live frame does not regress the row") {
            withHandler { handler, db ->
                // The live SSE tail already delivered the fresher write (rev 6).
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "t1",
                        revision = 6L,
                        occurredAt = 2L,
                        payload = tag(name = "fresh", revision = 6L),
                    ),
                )

                // A concurrently-running from-zero catch-up page, read before the live write
                // landed, commits second — carrying the stale rev-5 snapshot.
                handler.onCatchUpItem(tag(name = "stale", revision = 5L), isTombstone = false)

                val row = db.tagDao().getById("t1")
                row.shouldNotBeNull()
                row.name shouldBe "fresh"
                row.revision shouldBe 6L
            }
        }

        test("stale catch-up tombstone after a live restore does not re-delete the row") {
            withHandler { handler, db ->
                // The live SSE tail restored the row at rev 6 (e.g. a re-add after a prior removal).
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "t1",
                        revision = 6L,
                        occurredAt = 2L,
                        payload = tag(name = "restored", revision = 6L),
                    ),
                )

                // A stale from-zero catch-up page still carries the pre-restore tombstone at rev 5.
                handler.onCatchUpItem(
                    tag(revision = 5L, deletedAt = 100L),
                    isTombstone = true,
                )

                // getById filters tombstones — a non-null result proves the row is still live.
                db.tagDao().getById("t1").shouldNotBeNull()
            }
        }

        test("an equal-revision catch-up item still applies — digest-repair semantics") {
            withHandler { handler, db ->
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "t1",
                        revision = 6L,
                        occurredAt = 2L,
                        payload = tag(name = "before-repair", revision = 6L),
                    ),
                )

                // A from-zero re-pull can legitimately rewrite content at an unchanged revision
                // (content-only drift repair) — the strict guard must let this through.
                handler.onCatchUpItem(tag(name = "after-repair", revision = 6L), isTombstone = false)

                val row = db.tagDao().getById("t1")
                row.shouldNotBeNull()
                row.name shouldBe "after-repair"
            }
        }

        test("a first-sight catch-up item applies — no local row means no staleness") {
            withHandler { handler, db ->
                handler.onCatchUpItem(tag(name = "brand-new", revision = 5L), isTombstone = false)

                val row = db.tagDao().getById("t1")
                row.shouldNotBeNull()
                row.name shouldBe "brand-new"
            }
        }

        test("composite-key (book_tags): a stale catch-up junction tombstone after a fresher live write is skipped") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val handler =
                        bookTagsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

                    // The live SSE tail already delivered a fresher junction write (rev 6, still live).
                    handler.onEvent(
                        SyncEvent.Updated(
                            id = "b1:t1",
                            revision = 6L,
                            occurredAt = 2L,
                            payload = BookTagSyncPayload(bookId = "b1", tagId = "t1", createdAt = 1L, revision = 6L),
                        ),
                    )

                    // A stale from-zero catch-up page still carries the pre-restore tombstone at rev 5.
                    handler.onCatchUpItem(
                        BookTagSyncPayload(
                            bookId = "b1",
                            tagId = "t1",
                            createdAt = 1L,
                            revision = 5L,
                            deletedAt = 100L,
                        ),
                        isTombstone = true,
                    )

                    val row = db.bookTagDao().findByKey("b1", "t1")
                    row.shouldNotBeNull()
                    row.deletedAt.shouldBeNull()
                } finally {
                    db.close()
                }
            }
        }

        test("books (ServerWins): a stale inbound event does not regress the row's revision metadata") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

                    handler.onEvent(
                        SyncEvent.Created(id = "b1", revision = 1L, occurredAt = 1L, payload = bookPayload(revision = 1L)),
                    )

                    // The live SSE tail already delivered a fresher write (rev 6).
                    handler.onEvent(
                        SyncEvent.Updated(id = "b1", revision = 6L, occurredAt = 2L, payload = bookPayload(revision = 6L)),
                    )

                    // A stale inbound frame (rev 5) must be skipped by the revision guard, not applied.
                    handler.onEvent(
                        SyncEvent.Updated(id = "b1", revision = 5L, occurredAt = 3L, payload = bookPayload(revision = 5L)),
                    )

                    db.bookDao().getById(BookId("b1"))?.revision shouldBe 6L
                } finally {
                    db.close()
                }
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<Tag>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            val handler = tagsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
            block(handler, db)
        } finally {
            db.close()
        }
    }

private fun tag(
    id: String = "t1",
    name: String = "n",
    revision: Long = 5L,
    deletedAt: Long? = null,
) = Tag(id = id, name = name, slug = "s", revision = revision, deletedAt = deletedAt, updatedAt = 100L)

private fun bookPayload(revision: Long) =
    BookSyncPayload(
        id = "b1",
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Test Book",
        sortTitle = "Test Book",
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
        rootRelPath = "books/b1",
        inode = null,
        scannedAt = 1L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        documents = emptyList(),
        revision = revision,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )
