@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import app.cash.sqldelight.db.SqlDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Gating and happy-path tests for [ReadingOrderServiceImpl].
 *
 * Uses a real in-memory migrated SQLite database + real repositories; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [actAs] rebinds
 * the service to a chosen `(userId, role)` so a single test can exercise multiple
 * callers.
 *
 * The deep access-filtering matrix (non-owner public/private views, discovery
 * hiding) lives in `ReadingOrderAccessTest`; this covers ownership gating,
 * validation, `attribution` persistence, and the owner happy path.
 */
class ReadingOrderServiceTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
        ): ReadingOrderServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ReadingOrderServiceImpl(
                readingOrderRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry),
                readingOrderBookRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry),
                followRepo = ReadingOrderFollowRepository(db = sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                readAssembler = ReadingOrderReadAssembler(sql),
                clock = fixedClock,
                principal = principalFor("u1"),
            )
        }

        fun makeServiceWithRecorder(
            sql: ListenUpDatabase,
            driver: SqlDriver,
        ): ReadingOrderServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ReadingOrderServiceImpl(
                readingOrderRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry),
                readingOrderBookRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry),
                followRepo = ReadingOrderFollowRepository(db = sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                readAssembler = ReadingOrderReadAssembler(sql),
                clock = fixedClock,
                principal = principalFor("u1"),
                activityRecorder =
                    ActivityRecorder(syncRepo = ActivitySyncRepository(db = sql, bus = bus, registry = registry, driver = driver)),
            )
        }

        fun ReadingOrderServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): ReadingOrderServiceImpl = copyWith(principalFor(userId, role))

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        // ── create / list / update / delete ────────────────────────────────────

        test("createReadingOrder then listMyReadingOrders shows it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val created =
                        service
                            .createReadingOrder(
                                name = "Cosmere — Chronological",
                                description = "later",
                                attribution = "u/Argent",
                                isPrivate = false,
                            ).value()
                    created.name shouldBe "Cosmere — Chronological"
                    created.description shouldBe "later"
                    created.attribution shouldBe "u/Argent"
                    created.bookCount shouldBe 0

                    val mine = service.listMyReadingOrders().value()
                    mine shouldHaveSize 1
                    mine.first().id shouldBe created.id
                }
            }
        }

        test("createReadingOrder of a public order does not record activity when no ActivityRecorder is wired") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val activities = ActivityRepository(db = sql)
                    makeService(sql, driver)
                        .actAs("u1")
                        .createReadingOrder(name = "Winter Reads", isPrivate = false)
                        .value()

                    activities.page(before = null, limit = 50).filter { it.type == ActivityType.SHELF_CREATED }.shouldHaveSize(0)
                }
            }
        }

        test("updateReadingOrder changes name, description, attribution, and privacy") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val created = service.createReadingOrder(name = "Old", isPrivate = false).value()

                    val updated =
                        service
                            .updateReadingOrder(
                                created.id,
                                name = "New",
                                description = "fresh",
                                attribution = "u/NewGuy",
                                isPrivate = true,
                            ).value()
                    updated.name shouldBe "New"
                    updated.description shouldBe "fresh"
                    updated.attribution shouldBe "u/NewGuy"
                    updated.isPrivate shouldBe true
                }
            }
        }

        test("deleteReadingOrder removes it from listMyReadingOrders") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val created = service.createReadingOrder(name = "Temp").value()

                    service.deleteReadingOrder(created.id).value()
                    service.listMyReadingOrders().value().shouldHaveSize(0)
                }
            }
        }

        test("createReadingOrder with a blank name fails with InvalidName") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val result = makeService(sql, driver).actAs("u1").createReadingOrder(name = "   ")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.InvalidName>()
                }
            }
        }

        // ── book membership + ordering (owner) ─────────────────────────────────

        test("addBook, reorder, and getReadingOrder reflect the owner's ordering with titles and authors") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                seedAuthor(sql, bookId = "b1", contributorId = "c1", name = "Ada Lovelace")
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
                    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "u1-col",
                            libraryId = "test-library",
                            ownerId = "u1",
                            name = "u1 Collection",
                            isInbox = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "u1-col", bookId = "b1", createdAt = 0L, revision = 0L),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "u1-col", bookId = "b2", createdAt = 0L, revision = 0L),
                    )

                    val service = makeService(sql, driver).actAs("u1")
                    val order = service.createReadingOrder(name = "Reading").value()

                    service.addBookToReadingOrder(order.id, BookId("b1")).value()
                    service.addBookToReadingOrder(order.id, BookId("b2")).value()

                    val initial = service.getReadingOrder(order.id).value()
                    initial.isOwner shouldBe true
                    initial.bookCount shouldBe 2
                    initial.books.map { it.bookId } shouldContainExactly listOf("b1", "b2")
                    initial.books.first().authors shouldContainExactly listOf("Ada Lovelace")

                    service.reorderReadingOrderBooks(order.id, listOf(BookId("b2"), BookId("b1"))).value()
                    val reordered = service.getReadingOrder(order.id).value()
                    reordered.books.map { it.bookId } shouldContainExactly listOf("b2", "b1")

                    service.removeBookFromReadingOrder(order.id, BookId("b2")).value()
                    service
                        .getReadingOrder(order.id)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("b1")
                }
            }
        }

        test("addBookToReadingOrder with a book the owner cannot access fails with NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("hidden")
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
                    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "priv",
                            libraryId = "test-library",
                            ownerId = "u2",
                            name = "Private",
                            isInbox = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "priv", bookId = "hidden", createdAt = 0L, revision = 0L),
                    )

                    val service = makeService(sql, driver).actAs("u1")
                    val order = service.createReadingOrder(name = "Reading").value()

                    val result = service.addBookToReadingOrder(order.id, BookId("hidden"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.NotFound>()
                }
            }
        }

        // ── ownership gating (Forbidden vs NotFound) ───────────────────────────

        test("a non-owner MEMBER is Forbidden from mutating another user's reading order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("b1")
                runTest {
                    val owner = makeService(sql, driver).actAs("u1")
                    val order = owner.createReadingOrder(name = "Mine").value()

                    val intruder = makeService(sql, driver).actAs("u2")
                    intruder.updateReadingOrder(order.id, "Hijacked", "", "", false).expectForbidden()
                    intruder.deleteReadingOrder(order.id).expectForbidden()
                    intruder.addBookToReadingOrder(order.id, BookId("b1")).expectForbidden()
                    intruder.removeBookFromReadingOrder(order.id, BookId("b1")).expectForbidden()
                    intruder.reorderReadingOrderBooks(order.id, listOf(BookId("b1"))).expectForbidden()
                }
            }
        }

        test("mutating a non-existent reading order fails with NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val result = service.updateReadingOrder(ReadingOrderId("ghost"), "x", "", "", false)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.NotFound>()
                }
            }
        }

        test("listMyReadingOrders returns only the caller's own reading orders") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val base = makeService(sql, driver)
                    base.actAs("u1").createReadingOrder(name = "u1 order").value()
                    base.actAs("u2").createReadingOrder(name = "u2 order").value()

                    val u1Orders = base.actAs("u1").listMyReadingOrders().value()
                    u1Orders shouldHaveSize 1
                    u1Orders.first().name shouldBe "u1 order"
                }
            }
        }

        // ── getReadingOrder access basics (full matrix is ReadingOrderAccessTest) ──

        test("getReadingOrder returns NotFound to a non-owner on a private order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val base = makeService(sql, driver)
                    val order = base.actAs("u1").createReadingOrder(name = "Secret", isPrivate = true).value()

                    val result = base.actAs("u2").getReadingOrder(order.id)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.NotFound>()
                }
            }
        }
    })

/** Asserts the result is a [ReadingOrderError.Forbidden] failure. */
private fun AppResult<*>.expectForbidden() {
    this.shouldBeInstanceOf<AppResult.Failure>()
    error.shouldBeInstanceOf<ReadingOrderError.Forbidden>()
}

/** Seeds an `author`-role contributor credit linking [contributorId] ([name]) to [bookId]. */
private fun seedAuthor(
    sql: ListenUpDatabase,
    bookId: String,
    contributorId: String,
    name: String,
) {
    sql.transaction {
        sql.contributorsQueries.insert(
            id = contributorId,
            normalized_name = name.lowercase(),
            name = name,
            sort_name = name,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            image_path = null,
            image_blur_hash = null,
            birth_date = null,
            death_date = null,
            website = null,
        )
        sql.bookContributorsQueries.insert(
            book_id = bookId,
            contributor_id = contributorId,
            role = "author",
            credited_as = null,
            ordinal = 0,
        )
    }
}
