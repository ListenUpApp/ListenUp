package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.api.sync.SyncPayload
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.activitiesDomain
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.collectionSharesDomain
import com.calypsan.listenup.client.data.sync.domains.collectionsDomain
import com.calypsan.listenup.client.data.sync.domains.presenceDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)

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
                harness.engine.handleAccessChanged(null)
                val digestAfterFirst = db.activityDao().digestRows(Long.MAX_VALUE).toSet()

                // Second reconcile against the SAME accessible set must change nothing: the digest the
                // server would compare against is now stable, so `activities` converges exactly like
                // `books` — no row flip-flops, no permanent (id, revision) drift.
                harness.engine.handleAccessChanged(null)

                db.activityDao().digestRows(Long.MAX_VALUE).toSet() shouldBe digestAfterFirst
                db.activityDao().liveIds() shouldBe listOf("a1")
            }
        }

        test("activities: a re-granted (live re-delivered) activity un-tombstones after an access prune") {
            withReconcileEngine { harness, db, _ ->
                val handler = activitiesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(activityPayload("a1", bookId = "b1"), isTombstone = false)

                // Share revoked → the prune tombstones a1 (it dropped out of the accessible set).
                harness.fakeCatchUp.accessibleByDomain["activities"] = emptySet()
                harness.engine.handleAccessChanged(null)
                db.activityDao().getById("a1")!!.deletedAt shouldNotBe null

                // Share restored → catch-up re-delivers the SAME activity LIVE (deletedAt = null).
                // The apply must resurrect the row, not leave it stranded-tombstoned forever.
                handler.onCatchUpItem(activityPayload("a1", bookId = "b1"), isTombstone = false)

                db.activityDao().getById("a1")!!.deletedAt shouldBe null
                db.activityDao().liveIds() shouldBe listOf("a1")
            }
        }

        test("activities: the prune tombstones EVERY doomed row across chunk boundaries (>900)") {
            withReconcileEngine { harness, db, _ ->
                // Seed more live rows than one SQLite bind-var chunk holds (900), so the prune must
                // span multiple chunks — a single NOT IN would either overflow the binder or (if it
                // fit) only ever touch one chunk's worth here we prove full coverage.
                val total = 1000
                repeat(total) { i -> db.activityDao().upsert(activityEntity("a$i")) }
                db.activityDao().liveIds() shouldHaveSize total

                // Only the first 10 stay accessible; the other 990 must all be tombstoned.
                val accessible = (0 until 10).map { "a$it" }.toSet()
                harness.fakeCatchUp.accessibleByDomain["activities"] = accessible
                harness.engine.handleAccessChanged(null)

                db.activityDao().liveIds().toSet() shouldBe accessible
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
                harness.engine.handleAccessChanged(null)

                store.getCursor("books") shouldBe 42L
            }
        }

        // ---- Scoped delta path -------------------------------------------------------------------

        test("delta: fetches exactly the scoped ids — collections → collection_books → books → activities, in dependency order") {
            withReconcileEngine { harness, _, _ ->
                // A returned set per domain so the prune step is a no-op; we assert only the fetch shape.
                harness.fakeCatchUp.returnedByDomain["collections"] = setOf("c1")
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")

                harness.engine.handleAccessChanged(AccessScope(collectionIds = listOf("c1"), bookIds = listOf("b1")))

                harness.fakeCatchUp.fetches shouldBe
                    listOf(
                        RecordedFetch("collections", TargetedFetch.ByIds(listOf("c1"))),
                        RecordedFetch("collection_books", TargetedFetch.ByCollectionIds(listOf("c1"))),
                        RecordedFetch("books", TargetedFetch.ByIds(listOf("b1"))),
                        RecordedFetch("activities", TargetedFetch.ByBookIds(listOf("b1"))),
                    )
                // A scoped delta never re-derives the whole library — the coarse sweep must not run.
                harness.fakeCatchUp.coarseCalls.shouldBeEmpty()
            }
        }

        test("delta drift-killer: the fetched domains equal the registry's Targeted set — a new gated domain auto-enters") {
            withReconcileEngine { harness, _, _ ->
                // The expectation is DERIVED from the registry, not hand-copied: every access-gated
                // handler whose declared delta policy is Targeted must be fetched by a full-scope delta.
                // A future gated domain declaring Targeted therefore auto-enters this assertion — the
                // delta path can never silently forget it.
                val expected =
                    harness.registry
                        .accessFilteredHandlers()
                        .filter { (it as AccessFilteredSyncHandler).deltaPolicy is AccessDeltaPolicy.Targeted }
                        .map { it.domainName }
                        .toSet()

                harness.engine.handleAccessChanged(AccessScope(collectionIds = listOf("c1"), bookIds = listOf("b1")))

                harness.fakeCatchUp.fetches
                    .map { it.domain }
                    .toSet() shouldBe expected
            }
        }

        test("delta GRANT convergence: a newly-accessible activity goes LIVE within the delta pass (the bug)") {
            withReconcileEngine { harness, db, _ ->
                // No local activity is seeded — the grant is brand new to this client.
                db.activityDao().liveIds().shouldBeEmpty()

                // The book was just granted; the server's access-filtered fetch returns its activity row.
                // The fake applies these payloads through the real handler (exactly like the production
                // fetchTransient), so a returned-and-applied row lands LIVE in Room.
                harness.fakeCatchUp.fetchPayloadsByDomain["activities"] =
                    listOf(activityPayload("aGranted", bookId = "bGranted"))

                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("bGranted")))

                // Convergence happened in the delta pass itself — no digest/coarse backstop was needed.
                harness.fakeCatchUp.coarseCalls.shouldBeEmpty()
                db.activityDao().getById("aGranted")!!.deletedAt shouldBe null
                db.activityDao().liveIds() shouldBe listOf("aGranted")
            }
        }

        test("delta: a requested book that does not come back is tombstoned; the returned one stays live") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                handler.onCatchUpItem(bookPayload("b2"), isTombstone = false)

                // Both are in scope, but only b1 comes back accessible — b2's share was revoked.
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("b1", "b2")))

                db.bookDao().getById(BookId("b1"))!!.deletedAt shouldBe null
                db.bookDao().getById(BookId("b2"))!!.deletedAt shouldNotBe null
            }
        }

        test("delta: a live book OUTSIDE the scope is NEVER touched — the substrate-protection invariant") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                // b1 is the scoped, now-revoked book. bSubstrate is a live public ALL_BOOKS book the
                // client mirrors but the delta never names — it MUST survive the prune.
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                handler.onCatchUpItem(bookPayload("bSubstrate"), isTombstone = false)

                // b1 revoked → returns nothing. The scope names ONLY b1, so bSubstrate is not a candidate.
                harness.fakeCatchUp.returnedByDomain["books"] = emptySet()
                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("b1")))

                db.bookDao().getById(BookId("b1"))!!.deletedAt shouldNotBe null
                // The load-bearing assertion: a book outside the delta scope is untouched — no over-prune.
                db.bookDao().getById(BookId("bSubstrate")).shouldNotBeNull()
                db.bookDao().getById(BookId("bSubstrate"))!!.deletedAt shouldBe null
            }
        }

        test("delta: the books prune cascades — readership for a revoked scoped book is dropped") {
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

                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("b1", "b2")))

                db.bookReadershipDao().observeForBook("b1").first() shouldHaveSize 1
                db
                    .bookReadershipDao()
                    .observeForBook("b2")
                    .first()
                    .shouldBeEmpty()
            }
        }

        test("delta: revoking a scoped book self-prunes its activity via pruneWithin — book-less + out-of-scope survive") {
            withReconcileEngine { harness, db, _ ->
                val activities = activitiesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                activities.onCatchUpItem(activityPayload("a2", bookId = "b2"), isTombstone = false)
                // A book-less activity has no gating book, so it is never a prune candidate.
                activities.onCatchUpItem(activityPayload("aNoBook", bookId = null), isTombstone = false)
                // An activity on a book OUTSIDE the delta scope must never be a candidate either.
                activities.onCatchUpItem(activityPayload("aOther", bookId = "bOther"), isTombstone = false)

                // b2 revoked → the delta fetches activities by book_id and gets nothing back for b2, so
                // the requested-but-not-returned a2 is tombstoned by pruneWithin against the b2-scoped
                // candidate set. This is the new mechanism — no books afterPrune cascade is involved.
                harness.fakeCatchUp.returnedByDomain["activities"] = emptySet()
                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("b2")))

                db.activityDao().getById("a2")!!.deletedAt shouldNotBe null
                db.activityDao().getById("aNoBook")!!.deletedAt shouldBe null
                // The load-bearing substrate assertion: an out-of-scope book's activity is untouched.
                db.activityDao().getById("aOther")!!.deletedAt shouldBe null
            }
        }

        test("coarse frame re-derives every access-gated domain — the 5-domain sweep") {
            withReconcileEngine { harness, _, _ ->
                harness.engine.handleAccessChanged(null)

                harness.fakeCatchUp.coarseCalls.toSet() shouldBe
                    setOf("books", "collections", "collection_books", "collection_shares", "activities")
                // Coarse never issues a targeted fetch.
                harness.fakeCatchUp.fetches.shouldBeEmpty()
            }
        }

        // ---- Presence refresh on AccessChanged (the revoke-latency fix) --------------------------

        test("presence refreshes exactly once, AFTER the access reconcile's domain fetches complete") {
            withReconcileEngine { harness, _, _ ->
                harness.presence.signal.test {
                    // A direct (non-launched) call: when it returns, the reconcile loop AND the
                    // end-of-loop presence refresh have both run.
                    harness.engine.handleAccessChanged(null)

                    // Exactly one presence refresh for the single leader activation.
                    awaitItem()
                    expectNoEvents()

                    // Ordering proof: the refresh captured the FULL 5-domain coarse sweep already in the
                    // recorded trail. If the refresh were placed before the reconcile (top of
                    // handleAccessChanged), this snapshot would be empty and the assertion would fail.
                    harness.coarseCallsAtPresenceRefresh.single().toSet() shouldBe
                        setOf("books", "collections", "collection_books", "collection_shares", "activities")
                }
            }
        }

        test("presence refresh coalesces: two AccessChanged frames fold into one leader activation, one refresh") {
            withReconcileEngine { harness, _, _ ->
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("ba", "bb", "bc")
                val gate = CompletableDeferred<Unit>()
                harness.fakeCatchUp.gate = gate

                harness.presence.signal.test {
                    coroutineScope {
                        // Leader reconciles [ba] and parks on the gate at its books fetch.
                        val leader = launch { harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("ba"))) }
                        while (harness.fakeCatchUp.fetches.isEmpty()) yield()
                        // Two more frames land while the leader is parked → they fold into its follow-up pass,
                        // NOT their own leader activation (and NOT their own presence refresh).
                        harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bb")))
                        harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bc")))
                        gate.complete(Unit)
                        leader.join()
                    }

                    // One leader activation drained [ba] then the unioned {bb, bc} follow-up; the presence
                    // refresh fired once at the END of that single activation — coalesced, not once-per-frame.
                    awaitItem()
                    expectNoEvents()
                }
            }
        }

        test("coalesce: two deltas arriving mid-reconcile fold into ONE unioned follow-up fetch") {
            withReconcileEngine { harness, _, _ ->
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("ba", "bb", "bc")
                val gate = CompletableDeferred<Unit>()
                harness.fakeCatchUp.gate = gate

                coroutineScope {
                    // Leader pass reconciles [ba] and parks on the gate at its books fetch.
                    val leader = launch { harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("ba"))) }
                    while (harness.fakeCatchUp.fetches.isEmpty()) yield()
                    // Two frames land while the leader is parked → they accumulate together.
                    harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bb")))
                    harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bc")))
                    gate.complete(Unit)
                    leader.join()
                }

                // Exactly two books passes (the coalescer's unit of work): the leader's [ba], then ONE
                // follow-up over the union {bb, bc}. Activities rides the same Books axis, so it is
                // filtered out here — the coalescing invariant is about the axis, not the domain count.
                val bookFetches = harness.fakeCatchUp.fetches.filter { it.domain == "books" }
                bookFetches shouldHaveSize 2
                bookFetches[0].fetch shouldBe TargetedFetch.ByIds(listOf("ba"))
                (bookFetches[1].fetch as TargetedFetch.ByIds).ids.toSet() shouldBe setOf("bb", "bc")
            }
        }

        test("coalesce: a coarse frame arriving mid-delta POISONS the follow-up to a single coarse pass") {
            withReconcileEngine { harness, _, _ ->
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("ba")
                val gate = CompletableDeferred<Unit>()
                harness.fakeCatchUp.gate = gate

                coroutineScope {
                    val leader = launch { harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("ba"))) }
                    while (harness.fakeCatchUp.fetches.isEmpty()) yield()
                    // A coarse frame AND another delta land — coarse must win the follow-up.
                    harness.engine.handleAccessChanged(null)
                    harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bb")))
                    gate.complete(Unit)
                    leader.join()
                }

                // Only the leader's single delta books fetch ran; the follow-up was a coarse 5-domain
                // sweep, NOT a targeted fetch of bb. (Activities shares the Books axis; filter to books.)
                val bookFetches = harness.fakeCatchUp.fetches.filter { it.domain == "books" }
                bookFetches shouldHaveSize 1
                bookFetches[0].fetch shouldBe TargetedFetch.ByIds(listOf("ba"))
                harness.fakeCatchUp.coarseCalls shouldHaveSize 5
            }
        }

        test("a cancelled reconcile does not wedge the coalesce mutex — the next frame still leads") {
            withReconcileEngine { harness, _, _ ->
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("ba", "bb")
                val gate = CompletableDeferred<Unit>()
                harness.fakeCatchUp.gate = gate

                coroutineScope {
                    val leader = launch { harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("ba"))) }
                    while (harness.fakeCatchUp.fetches.isEmpty()) yield()
                    // Cancel mid-reconcile (parked on the gate). The NonCancellable finally must reset the flags.
                    leader.cancelAndJoin()
                }

                // If accessChangedRunning were stranded true, this frame would find a "running" leader and
                // no-op — no fetch. It leading + fetching proves the mutex state was cleaned up.
                harness.fakeCatchUp.fetches.clear()
                harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("bb")))

                val bookFetches = harness.fakeCatchUp.fetches.filter { it.domain == "books" }
                bookFetches shouldHaveSize 1
                bookFetches[0].fetch shouldBe TargetedFetch.ByIds(listOf("bb"))
            }
        }

        // ---- Transient-failure re-queue (plan 101) --------------------------------------------------

        test("delta requeue: a failed scoped fetch is retried on the next drain and the prune completes") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)
                handler.onCatchUpItem(bookPayload("b2"), isTombstone = false)

                // First books fetch fails (transient); the retry succeeds returning only b1.
                harness.fakeCatchUp.failNextByDomain["books"] = 1
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged(AccessScope(collectionIds = emptyList(), bookIds = listOf("b1", "b2")))

                // Two books attempts were made over the same scope (activities rides the same axis)...
                val bookFetches = harness.fakeCatchUp.fetches.filter { it.domain == "books" }
                bookFetches shouldHaveSize 2
                (bookFetches[1].fetch as TargetedFetch.ByIds).ids.toSet() shouldBe setOf("b1", "b2")
                // ...and the retry's prune actually evicted the revoked book.
                db.bookDao().getById(BookId("b1"))!!.deletedAt shouldBe null
                db.bookDao().getById(BookId("b2"))!!.deletedAt shouldNotBe null
            }
        }

        test("coarse requeue: a failed coarse domain triggers ONE follow-up coarse pass that prunes") {
            withReconcileEngine { harness, db, _ ->
                val handler = collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(collectionPayload("c1"), isTombstone = false)
                handler.onCatchUpItem(collectionPayload("c2"), isTombstone = false)

                harness.fakeCatchUp.failNextByDomain["collections"] = 1
                harness.fakeCatchUp.accessibleByDomain["collections"] = setOf("c1")
                harness.engine.handleAccessChanged(null)

                // The coarse sweep ran twice for collections (failed pass + retry pass).
                harness.fakeCatchUp.coarseCalls.count { it == "collections" } shouldBe 2
                db.collectionDao().getById("c1").shouldNotBeNull()
                db.collectionDao().getById("c2") shouldBe null
            }
        }

        test("delta requeue is bounded: persistent failure stops after ONE retry and a later frame still leads") {
            withReconcileEngine { harness, db, _ ->
                val handler =
                    booksDomain(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                    ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
                handler.onCatchUpItem(bookPayload("b1"), isTombstone = false)

                // Every attempt fails: leader + exactly one retry, then give up (reconnect backstop owns it).
                harness.fakeCatchUp.failNextByDomain["books"] = 10
                harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("b1")))
                harness.fakeCatchUp.fetches.count { it.domain == "books" } shouldBe 2

                // A fresh frame gets a fresh leader with a fresh retry budget — nothing is wedged.
                harness.fakeCatchUp.fetches.clear()
                harness.fakeCatchUp.failNextByDomain.clear()
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")
                harness.engine.handleAccessChanged(AccessScope(emptyList(), listOf("b1")))
                harness.fakeCatchUp.fetches.count { it.domain == "books" } shouldBe 1
            }
        }

        test("delta requeue: only the FAILED axis's ids are refetched — the succeeded axis is not") {
            withReconcileEngine { harness, _, _ ->
                // The Collections axis (collections + collection_books) succeeds; the Books axis fails
                // once via books then succeeds. The remainder is folded per AXIS, so only the Books-axis
                // ids requeue — the succeeded Collections axis is never refetched.
                harness.fakeCatchUp.returnedByDomain["collections"] = setOf("c1")
                harness.fakeCatchUp.returnedByDomain["books"] = setOf("b1")
                harness.fakeCatchUp.failNextByDomain["books"] = 1

                harness.engine.handleAccessChanged(AccessScope(collectionIds = listOf("c1"), bookIds = listOf("b1")))

                // Pass 1: collections + collection_books + books(FAILED) + activities. Pass 2 (Books-axis
                // requeue): books + activities only — collections/collection_books do not run again.
                harness.fakeCatchUp.fetches.map { it.domain } shouldBe
                    listOf("collections", "collection_books", "books", "activities", "books", "activities")
            }
        }
    })

/** A targeted fetch the fake observed, for assertions. */
private data class RecordedFetch(
    val domain: String,
    val fetch: TargetedFetch,
)

/**
 * Fake [CatchUp]: the coarse pass ([catchUpTransient]) returns a controlled accessible set per
 * domain; the delta pass ([fetchTransient]) records each targeted fetch and returns a controlled
 * "still-accessible returned" set per domain (defaulting to the accessible set). A latch lets a test
 * hold one reconcile mid-flight to exercise the coalescer.
 */
private class FakeReconcileCatchUp : CatchUp {
    val accessibleByDomain: MutableMap<String, Set<String>> = mutableMapOf()
    val returnedByDomain: MutableMap<String, Set<String>> = mutableMapOf()

    /**
     * Payloads a targeted fetch "returns" per domain. When set, [fetchTransient] APPLIES them through
     * the real handler (exactly like the production client) and derives the returned id set from them
     * — so a granted row actually lands live in Room. Domains without an entry use [returnedByDomain].
     */
    val fetchPayloadsByDomain: MutableMap<String, List<SyncPayload>> = mutableMapOf()
    val fetches: MutableList<RecordedFetch> = mutableListOf()

    /** The domain names the coarse pass ([catchUpTransient]) re-derived, in order — for the 5-domain sweep assertion. */
    val coarseCalls: MutableList<String> = mutableListOf()

    /** When set, the FIRST fetchTransient awaits this before returning — a hook to interleave a second frame. */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    /** Domains whose next N calls (targeted or coarse) fail with a retryable SyncError. Decremented per call. */
    val failNextByDomain: MutableMap<String, Int> = mutableMapOf()

    private fun consumeFailure(domain: String): Boolean {
        val remaining = failNextByDomain[domain] ?: return false
        if (remaining <= 0) return false
        failNextByDomain[domain] = remaining - 1
        return true
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> {
        coarseCalls += handler.domainName
        if (consumeFailure(handler.domainName)) return AppResult.Failure(SyncError.SyncFailed())
        val ids = accessibleByDomain[handler.domainName] ?: emptySet()
        return AppResult.Success(ids)
    }

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        fetches += RecordedFetch(handler.domainName, fetch)
        gate?.let {
            gate = null
            it.await()
        }
        if (consumeFailure(handler.domainName)) return AppResult.Failure(SyncError.SyncFailed())
        fetchPayloadsByDomain[handler.domainName]?.let { payloads ->
            // Faithful to production fetchTransient: apply each returned payload through the handler
            // and collect the non-tombstone ids as the "still-accessible returned" set.
            @Suppress("UNCHECKED_CAST")
            val typed = handler as SyncDomainHandler<Any>
            val returnedIds = mutableSetOf<String>()
            for (payload in payloads) {
                val isTomb = (payload as? Tombstoned)?.deletedAt != null
                typed.onCatchUpItem(payload, isTomb)
                if (!isTomb) returnedIds += typed.syncId(payload)
            }
            return AppResult.Success(returnedIds)
        }
        val returned = returnedByDomain[handler.domainName] ?: accessibleByDomain[handler.domainName] ?: emptySet()
        return AppResult.Success(returned)
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
    /** The shared registry the five access-gated handlers registered under — the drift-killer derives its expectation from it. */
    val registry: ClientSyncDomainRegistry,
    /** The presence signal the access-sensitive router pings — subscribe with Turbine to observe refreshes. */
    val presence: PresenceRefreshSignal,
    /**
     * Snapshot of [FakeReconcileCatchUp.coarseCalls] captured at the instant each presence refresh fires.
     * Because the refresh runs at the END of the reconcile loop, a snapshot here proves the reconcile's
     * fetches already ran before the refresh — an empty snapshot would mean the refresh fired too early.
     */
    val coarseCallsAtPresenceRefresh: List<List<String>>,
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
            val presence = PresenceRefreshSignal()
            // Snapshot the coarse reconcile trail at the moment presence refreshes, so ordering
            // (refresh AFTER the reconcile's fetches) is provable from the recorded sequence.
            val coarseCallsAtPresenceRefresh = mutableListOf<List<String>>()
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
                    presenceRefreshSignal = presence,
                    scope = scope,
                    // Presence is the access-sensitive refreshed domain: the engine re-fires it at the end
                    // of an AccessChanged reconcile via refreshAccessSensitive(). Wire it so the test can
                    // observe the refresh (via the signal) and the reconcile state at refresh time.
                    refreshedRouter =
                        RefreshedDomainRouter(
                            listOf(
                                presenceDomain(
                                    ping = {
                                        coarseCallsAtPresenceRefresh += fakeCatchUp.coarseCalls.toList()
                                        presence.ping()
                                    },
                                ),
                            ),
                        ),
                )
            block(
                ReconcileHarness(engine, fakeCatchUp, registry, presence, coarseCallsAtPresenceRefresh),
                db,
                store,
            )
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

private fun activityEntity(id: String): ActivityEntity =
    ActivityEntity(
        id = id,
        userId = "author",
        type = "finished_book",
        occurredAt = 100L,
        bookId = "b-$id",
        isReread = false,
        durationMs = 0L,
        milestoneValue = 0,
        milestoneUnit = null,
        shelfId = null,
        shelfName = null,
        revision = 1L,
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
