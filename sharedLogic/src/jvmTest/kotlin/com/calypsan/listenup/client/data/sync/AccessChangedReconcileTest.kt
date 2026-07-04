package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.activitiesDomain
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.collectionSharesDomain
import com.calypsan.listenup.client.data.sync.domains.collectionsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * The load-bearing security piece: on `AccessChanged`, the engine
 * re-derives each access-gated domain via a TRANSIENT catch-up and prunes every locally-live
 * row no longer in the accessible set. A revoked share must actually evict the now-inaccessible
 * rows from Room.
 *
 * The fake [CatchUp] returns a controlled accessible id set per domain (standing in for the
 * server's access-filtered `pullSince`); the test asserts the engine tombstones what's gone and
 * leaves what remains untouched, and that the persisted cursor store is never touched.
 */
class AccessChangedReconcileTest :
    FunSpec({

        test("books: revoked book is tombstoned, accessible book remains live") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                handler.onCatchUpItem(bookPayload("b2"), isTombstone = false)
                db.bookDao().getById(BookId("b1")).shouldNotBeNull()
                db.bookDao().getById(BookId("b2")).shouldNotBeNull()

                // Server now reports only b1 accessible — b2's share was revoked.
                harness.fakeCatchUp.accessibleByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged()

                db.bookDao().getById(BookId("b1"))!!.deletedAt shouldBe null
                db.bookDao().getById(BookId("b2"))!!.deletedAt shouldNotBe null
            }
        }

        test("books: access prune deletes readership rows for revoked books") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                handler.onCatchUpItem(bookPayload("b2"), isTombstone = false)
                db.bookReadershipDao().upsertAll(
                    listOf(readershipRow("b1", "u1"), readershipRow("b2", "u1")),
                )

                // Server now reports only b1 accessible — b2's share was revoked.
                harness.fakeCatchUp.accessibleByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged()

                db.bookReadershipDao().observeForBook("b1").first() shouldHaveSize 1
                db
                    .bookReadershipDao()
                    .observeForBook("b2")
                    .first()
                    .shouldBeEmpty()
            }
        }

        test("collections: inaccessible collection is tombstoned, accessible one remains") {
            withReconcileEngine { harness, db, _ ->
                val handler = collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(collectionPayload("c1"), isTombstone = false)
                handler.onCatchUpItem(collectionPayload("c2"), isTombstone = false)

                harness.fakeCatchUp.accessibleByDomain["collections"] = setOf("c1")
                harness.engine.handleAccessChanged()

                db.collectionDao().getById("c1").shouldNotBeNull()
                // getById filters tombstones, so a pruned (soft-deleted) row reads as null.
                db.collectionDao().getById("c2") shouldBe null
            }
        }

        test("admin: everything accessible → pruneTo deletes nothing (no over-prune)") {
            withReconcileEngine { harness, db, _ ->
                val handler = collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(collectionPayload("c1"), isTombstone = false)
                handler.onCatchUpItem(collectionPayload("c2"), isTombstone = false)

                // Admin: access-filtered catch-up returns everything → pruneTo deletes nothing.
                harness.fakeCatchUp.accessibleByDomain["collections"] = setOf("c1", "c2")
                harness.engine.handleAccessChanged()

                db.collectionDao().getById("c1").shouldNotBeNull()
                db.collectionDao().getById("c2").shouldNotBeNull()
            }
        }

        test("total revocation: empty accessible set prunes EVERY local row (no under-prune)") {
            withReconcileEngine { harness, db, _ ->
                val handler = collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(collectionPayload("c1"), isTombstone = false)
                handler.onCatchUpItem(collectionPayload("c2"), isTombstone = false)
                db.collectionDao().liveIds().toSet() shouldBe setOf("c1", "c2")

                // Member lost ALL access — access-filtered catch-up returns the empty set.
                // The reconcile must evict EVERY local row; nothing may remain readable.
                harness.fakeCatchUp.accessibleByDomain["collections"] = emptySet()
                harness.engine.handleAccessChanged()

                db.collectionDao().liveIds().shouldBeEmpty()
                db.collectionDao().getById("c1") shouldBe null
                db.collectionDao().getById("c2") shouldBe null
            }
        }

        test("activities: a book that becomes inaccessible prunes its activity, the accessible one survives") {
            withReconcileEngine { harness, db, _ ->
                val handler = activitiesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                // a1's book stays accessible; a2's book (or share) is about to be revoked.
                handler.onCatchUpItem(activityPayload("a1", bookId = "b1"), isTombstone = false)
                handler.onCatchUpItem(activityPayload("a2", bookId = "b2"), isTombstone = false)
                db.activityDao().liveIds().toSet() shouldBe setOf("a1", "a2")

                // The access-filtered catch-up now returns only a1 — b2 was deleted / its share revoked,
                // so a2 dropped out of the member's accessible set.
                harness.fakeCatchUp.accessibleByDomain["activities"] = setOf("a1")
                harness.engine.handleAccessChanged()

                // a2 is tombstoned (soft-deleted), a1 remains live — same shape as books/collections.
                db.activityDao().getById("a1")!!.deletedAt shouldBe null
                db.activityDao().getById("a2")!!.deletedAt shouldNotBe null
                db.activityDao().liveIds() shouldBe listOf("a1")
            }
        }

        test("activities: the access prune converges — a second reconcile is a stable no-op (no permanent drift)") {
            withReconcileEngine { harness, db, _ ->
                val handler = activitiesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(activityPayload("a1", bookId = "b1"), isTombstone = false)
                handler.onCatchUpItem(activityPayload("a2", bookId = "b2"), isTombstone = false)

                // First reconcile prunes a2 to match the server's accessible set.
                harness.fakeCatchUp.accessibleByDomain["activities"] = setOf("a1")
                harness.engine.handleAccessChanged()
                val digestAfterFirst = db.activityDao().digestRows(Long.MAX_VALUE).toSet()

                // Second reconcile against the SAME accessible set must change nothing: the digest the
                // server would compare against is now stable, so `activities` converges exactly like
                // `books` — no row flip-flops, no permanent (id, revision) drift.
                harness.engine.handleAccessChanged()

                db.activityDao().digestRows(Long.MAX_VALUE).toSet() shouldBe digestAfterFirst
                db.activityDao().liveIds() shouldBe listOf("a1")
            }
        }

        test("the persisted cursor store is never touched by the reconcile") {
            withReconcileEngine { harness, db, store ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                store.setCursor("books", 42L)

                harness.fakeCatchUp.accessibleByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged()

                store.getCursor("books") shouldBe 42L
            }
        }
    })

/** Fake [CatchUp] whose transient pass returns a controlled accessible id set per domain. */
private class FakeReconcileCatchUp : CatchUp {
    val accessibleByDomain: MutableMap<String, Set<String>> = mutableMapOf()

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> {
        val ids = accessibleByDomain[handler.domainName] ?: emptySet()
        return AppResult.Success(ids)
    }

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal SSE fake — the reconcile path never touches it. */
private class FakeReconcileSse : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()

    override fun seedLastEventId(initial: Long?) = Unit

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun currentLastEventId(): Long? = null

    override suspend fun reseed(newLastEventId: Long?) = Unit

    override fun reconnectNow() = Unit
}

private data class ReconcileHarness(
    val engine: SyncEngine,
    val fakeCatchUp: FakeReconcileCatchUp,
)

private fun withReconcileEngine(block: suspend (ReconcileHarness, ListenUpDatabase, SyncCursorStore) -> Unit) =
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = createInMemoryTestDatabase()
        try {
            // Register the four access-gated handlers under one shared registry.
            val registry = ClientSyncDomainRegistry()
            val txn = RoomTransactionRunner(db)
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
            ).toHandler(transactionRunner = txn, registry = registry)
            collectionsDomain(db).toHandler(txn, registry)
            collectionBooksDomain(db).toHandler(txn, registry)
            collectionSharesDomain(db).toHandler(txn, registry)
            activitiesDomain(db).toHandler(txn, registry)

            val store = SyncCursorStore(db.syncCursorDao())
            val state = SyncEngineState()
            val queue =
                PendingOperationQueue(
                    dao = db.pendingOperationV2Dao(),
                    sender = PendingOperationSender { AppResult.Success(Unit) },
                )
            val dispatcher =
                SyncEventDispatcher(
                    registry = registry,
                    queue = queue,
                    state = state,
                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                )
            val fakeCatchUp = FakeReconcileCatchUp()
            val engine =
                SyncEngine(
                    registry = registry,
                    queue = queue,
                    state = state,
                    store = store,
                    catchUp = fakeCatchUp,
                    sseClient = FakeReconcileSse(),
                    reconciler = noopSyncReconciler(registry, store, fakeCatchUp),
                    dispatcher = dispatcher,
                    presenceRefreshSignal = PresenceRefreshSignal(),
                    scope = scope,
                )
            block(ReconcileHarness(engine, fakeCatchUp), db, store)
        } finally {
            scope.cancel()
            db.close()
        }
    }

private fun readershipRow(
    bookId: String,
    userId: String,
): BookReadershipEntity =
    BookReadershipEntity(
        bookId = bookId,
        userId = userId,
        displayName = "Reader $userId",
        avatarType = "auto",
        currentProgressPct = null,
        finishesJson = "",
        observedAt = 1L,
    )

private fun bookPayload(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = "Book $id",
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
        revision = 1L,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )

private fun activityPayload(
    id: String,
    bookId: String?,
): ActivitySyncPayload =
    ActivitySyncPayload(
        id = id,
        userId = "author",
        type = "finished_book",
        bookId = bookId,
        isReread = false,
        durationMs = 0L,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
        occurredAt = 100L,
        revision = 1L,
        createdAt = 1L,
        updatedAt = 100L,
        deletedAt = null,
    )

private fun collectionPayload(id: String): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "lib1",
        ownerId = "u1",
        name = "Collection $id",
        isInbox = false,
        revision = 1L,
        updatedAt = 100L,
        deletedAt = null,
    )
