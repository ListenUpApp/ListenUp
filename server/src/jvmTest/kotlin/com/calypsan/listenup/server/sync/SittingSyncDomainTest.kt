package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.SittingStart
import com.calypsan.listenup.api.sync.SittingSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.SittingReconciler
import com.calypsan.listenup.server.services.SittingRepository
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private const val MINUTE = 60_000L

/**
 * The `sittings` sync domain: catch-up and the live tail carry each sitting as it grows, and every read
 * path is gated on the sitting's book exactly as the activity feed is — a member never learns that
 * someone listened to a book they cannot open.
 */
class SittingSyncDomainTest :
    FunSpec({

        test("catch-up carries a sitting, then the same sitting again at a newer revision when it grows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("kyle")
                sql.seedTestBook("open")
                val repo = SittingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                val reconciler = SittingReconciler(db = sql, sittings = repo, bus = ChangeBus())
                runTest {
                    sql.listen("e1", "kyle", "open", from = 0, to = 10)
                    reconciler.reconcile("kyle", "open")
                    val first = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    val sitting = first.items.single()

                    sql.listen("e2", "kyle", "open", from = 20, to = 47)
                    reconciler.reconcile("kyle", "open")
                    val next = repo.pullSince(userId = null, cursor = checkNotNull(first.nextCursor), limit = 10).items.single()

                    next.id shouldBe sitting.id
                    next.listenedMs shouldBe 37 * MINUTE
                    (next.revision > sitting.revision) shouldBe true
                    repo.pullSince(userId = null, cursor = next.revision, limit = 10).items shouldBe emptyList()
                }
            }
        }

        test("a merged-away sitting reaches catch-up as a tombstone that names nobody and no book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("kyle")
                sql.seedTestBook("open")
                val repo = SittingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                val reconciler = SittingReconciler(db = sql, sittings = repo, bus = ChangeBus())
                runTest {
                    sql.listen("e1", "kyle", "open", from = 0, to = 10)
                    sql.listen("e2", "kyle", "open", from = 100, to = 110)
                    reconciler.reconcile("kyle", "open")
                    val cursor = checkNotNull(repo.pullSince(userId = null, cursor = 0L, limit = 10).nextCursor)

                    sql.listen("bridge", "kyle", "open", from = 50, to = 60)
                    reconciler.reconcile("kyle", "open")

                    val tombstone = repo.pullSince(userId = null, cursor = cursor, limit = 10).items.single { it.deletedAt != null }
                    tombstone shouldBe
                        SittingSyncPayload(
                            id = tombstone.id,
                            userId = "",
                            bookId = "",
                            startedAt = 0L,
                            endedAt = 0L,
                            listenedMs = 0L,
                            beganRead = SittingStart.NONE,
                            finishedRead = false,
                            revision = tombstone.revision,
                            deletedAt = tombstone.deletedAt,
                        )
                }
            }
        }

        test("the live tail publishes a sitting when it appears and when it grows, not for an unchanged rebuild") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("kyle")
                sql.seedTestBook("open")
                val bus = ChangeBus()
                val repo = SittingRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
                val reconciler = SittingReconciler(db = sql, sittings = repo, bus = bus)
                runTest {
                    sql.listen("e1", "kyle", "open", from = 0, to = 10)
                    reconciler.reconcile("kyle", "open")
                    reconciler.reconcile("kyle", "open")
                    sql.listen("e2", "kyle", "open", from = 20, to = 30)
                    reconciler.reconcile("kyle", "open")

                    bus
                        .subscribe()
                        .replayCache
                        .filter { it.repo.domainName == "sittings" }
                        .map { it.event::class } shouldContainExactly
                        listOf(SyncEvent.Created::class, SyncEvent.Updated::class)
                }
            }
        }

        test("the filtered pull, digest and targeted match serve sittings only on books the member can open") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open")
                sql.seedTestBook("secret")
                sql.seedTestUser("kyle")
                sql.seedTestUser("viewer")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                val repo = SittingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                val reconciler = SittingReconciler(db = sql, sittings = repo, bus = ChangeBus())
                runTest {
                    for (book in listOf("open", "secret")) {
                        sql.listen("e-$book", "kyle", book, from = 0, to = 10)
                        reconciler.reconcile("kyle", book)
                    }
                    val filter = accessFilterFor("sittings", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    repo.pullSince(userId = "viewer", cursor = 0L, limit = 10, extraWhere = filter).items.map {
                        it.bookId
                    } shouldBe listOf("open")
                    repo.digest(userId = "viewer", cursor = 100L, extraWhere = filter).count shouldBe 1
                    repo.digest(userId = null, cursor = 100L, extraWhere = null).count shouldBe 2
                    repo
                        .pullByIds(
                            userId = "viewer",
                            matchColumn = "book_id",
                            matchValues = listOf("open", "secret"),
                            extraWhere = filter,
                        ).items
                        .map { it.bookId } shouldBe listOf("open")
                }
            }
        }

        test("an admin's pull is unfiltered") {
            withSqlDatabase {
                accessFilterFor("sittings", "simon", UserRole.ADMIN) { BookAccessPolicy(sql, driver) } shouldBe null
            }
        }

        test("the firehose withholds a sitting on a book the member can't open, and passes tombstones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("open")
                sql.seedTestBook("secret")
                sql.seedTestUser("viewer")
                makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                val repo = SittingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                val policy = { BookAccessPolicy(sql, driver) }
                runTest {
                    fun created(bookId: String) =
                        BusEvent(
                            repo,
                            SyncEvent.Created(id = "s-$bookId", revision = 1, occurredAt = 1, payload = sitting("s-$bookId", bookId)),
                        )
                    firehoseGateReason(created("secret"), "viewer", UserRole.MEMBER, policy) shouldBe "bookJunction"
                    firehoseGateReason(created("open"), "viewer", UserRole.MEMBER, policy) shouldBe null
                    firehoseGateReason(created("secret"), "simon", UserRole.ADMIN, policy) shouldBe null
                    val deleted = BusEvent(repo, SyncEvent.Deleted(id = "s-secret", revision = 2, occurredAt = 2))
                    firehoseGateReason(deleted, "viewer", UserRole.MEMBER, policy) shouldBe null
                }
            }
        }

        test("sittings are per-row access-gated, support the BOOK_ID targeted match, and register as a domain") {
            ("sittings" in perRowAccessGatedSyncDomains) shouldBe true
            ("sittings" in BOOK_ID_MATCH_DOMAINS) shouldBe true
            withSqlDatabase {
                val registry = SyncRegistry()
                SittingRepository(db = sql, bus = ChangeBus(), registry = registry, driver = driver)
                registry.knownDomains() shouldBe listOf("sittings")
            }
        }
    })

private fun ListenUpDatabase.listen(
    id: String,
    userId: String,
    bookId: String,
    from: Long,
    to: Long,
) {
    listeningEventsQueries.insert(
        id = id,
        user_id = userId,
        book_id = bookId,
        start_position_ms = 0L,
        end_position_ms = 0L,
        started_at = from * MINUTE,
        ended_at = to * MINUTE,
        playback_speed = 1.0,
        tz = "UTC",
        device_label = null,
        revision = 1L,
        created_at = 1L,
        updated_at = 1L,
        deleted_at = null,
        client_op_id = null,
    )
}

private fun sitting(
    id: String,
    bookId: String,
) = SittingSyncPayload(
    id = id,
    userId = "kyle",
    bookId = bookId,
    startedAt = 0L,
    endedAt = 10 * MINUTE,
    listenedMs = 10 * MINUTE,
    revision = 1L,
)
