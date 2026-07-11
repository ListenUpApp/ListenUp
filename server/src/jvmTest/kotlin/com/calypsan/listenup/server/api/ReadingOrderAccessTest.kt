@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The access-hiding proof for [ReadingOrderServiceImpl.getReadingOrder] and
 * [ReadingOrderServiceImpl.discoverReadingOrders] — the headline requirement that a
 * viewer is never shown a book they cannot access, and is never even told a private
 * reading order exists. Mirrors [ShelfAccessTest] over the same shared fixture.
 *
 * Fixture, shared across the suite:
 * - **A** (`a`) — MEMBER, the reading-order owner.
 * - **B** (`b`) — MEMBER, an unrelated viewer holding a default ALL_BOOKS grant.
 * - **pub** — in ALL_BOOKS (the public substrate) → visible to every granted member, incl. B.
 * - **priv** — in a private collection owned by a third party; B has no grant → invisible to B.
 * - **glob** — also in ALL_BOOKS → visible to B.
 * - **S** — A's PUBLIC reading order, books `[pub, priv, glob]`.
 * - **P** — A's PRIVATE reading order, books `[pub]`.
 */
class ReadingOrderAccessTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun service(dbs: SqlTestDatabases): ReadingOrderServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ReadingOrderServiceImpl(
                readingOrderRepo = ReadingOrderRepository(db = dbs.sql, bus = bus, registry = registry),
                readingOrderBookRepo = ReadingOrderBookRepository(db = dbs.sql, bus = bus, registry = registry),
                followRepo = ReadingOrderFollowRepository(db = dbs.sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
                readAssembler = ReadingOrderReadAssembler(dbs.sql),
                clock = fixedClock,
                principal = principalFor("a"),
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

        fun fixtures(dbs: SqlTestDatabases): ReadingOrderFixtures {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ReadingOrderFixtures(
                collectionRepo = CollectionRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
                collectionBookRepo = CollectionBookRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
                grantRepo = CollectionGrantRepository(db = dbs.sql, bus = bus, registry = registry, driver = dbs.driver),
                readingOrderBookRepo = ReadingOrderBookRepository(db = dbs.sql, bus = bus, registry = registry),
                policy = BookAccessPolicy(dbs.sql, dbs.driver),
            )
        }

        suspend fun SqlTestDatabases.seedReadingOrder(
            f: ReadingOrderFixtures,
            ownerId: String,
            name: String,
            isPrivate: Boolean,
            bookIds: List<String>,
        ): ReadingOrderId {
            val order = service(this).actAs(ownerId).createReadingOrder(name = name, isPrivate = isPrivate).value()
            bookIds.forEach { f.readingOrderBookRepo.addBook(order.id.value, it, userId = ownerId) }
            return order.id
        }

        suspend fun SqlTestDatabases.seedBaseFixture(f: ReadingOrderFixtures): Pair<ReadingOrderId, ReadingOrderId> {
            f.collectionRepo.upsert(collectionFixture("priv-col", owner = "stranger"))
            f.collectionBookRepo.upsert(membership("priv-col", "priv"))
            f.collectionRepo.upsert(collectionFixture("all-books", owner = "system"))
            f.collectionBookRepo.upsert(membership("all-books", "pub"))
            f.collectionBookRepo.upsert(membership("all-books", "glob"))
            f.grantRepo.upsert(share("all-books-grant-b", "all-books", "b", SharePermission.Read))

            val orderS =
                seedReadingOrder(f, "a", name = "Shared Order", isPrivate = false, bookIds = listOf("pub", "priv", "glob"))
            val orderP = seedReadingOrder(f, "a", name = "Secret", isPrivate = true, bookIds = listOf("pub"))
            return orderS to orderP
        }

        test("non-owner sees a public reading order access-filtered to only visible books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (orderS, _) = seedBaseFixture(f)

                    val detail = service(this@withSqlDatabase).actAs("b").getReadingOrder(orderS).value()
                    detail.isOwner shouldBe false
                    detail.bookCount shouldBe 2
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }

        test("owner sees every book on their public reading order, including ones they can't access via collections") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (orderS, _) = seedBaseFixture(f)

                    val detail = service(this@withSqlDatabase).actAs("a").getReadingOrder(orderS).value()
                    detail.isOwner shouldBe true
                    detail.bookCount shouldBe 3
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")
                }
            }
        }

        test("admin sees every book on a non-owned public reading order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (orderS, _) = seedBaseFixture(f)

                    val detail = service(this@withSqlDatabase).actAs("b", UserRole.ADMIN).getReadingOrder(orderS).value()
                    detail.isOwner shouldBe false
                    detail.bookCount shouldBe 3
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")
                }
            }
        }

        test("a non-owner gets NotFound (never Forbidden, never an empty order) on a private reading order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (_, orderP) = seedBaseFixture(f)

                    val result = service(this@withSqlDatabase).actAs("b").getReadingOrder(orderP)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.NotFound>()
                }
            }
        }

        test("discovery filters another user's public reading order and excludes private + own orders") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (orderS, _) = seedBaseFixture(f)
                    seedReadingOrder(f, "b", name = "My Own", isPrivate = false, bookIds = listOf("pub"))

                    val discovered = service(this@withSqlDatabase).actAs("b").discoverReadingOrders().value()

                    discovered shouldHaveSize 1
                    val s = discovered.first()
                    s.readingOrder.id shouldBe orderS
                    s.ownerId shouldBe "a"
                    s.ownerDisplayName shouldBe "a"
                    s.readingOrder.bookCount shouldBe 2
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .getReadingOrder(s.readingOrder.id)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }

        test("discovery excludes a public reading order whose only books are all inaccessible") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    seedBaseFixture(f)
                    seedReadingOrder(f, "a", name = "All Hidden", isPrivate = false, bookIds = listOf("priv"))

                    val discovered = service(this@withSqlDatabase).actAs("b").discoverReadingOrders().value()

                    discovered shouldHaveSize 1
                    discovered.first().readingOrder.name shouldBe "Shared Order"
                }
            }
        }

        test("discoverReadingOrders(limit) returns exactly limit visible orders") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    seedBaseFixture(f)
                    seedReadingOrder(f, "a", name = "More", isPrivate = false, bookIds = listOf("glob"))
                    seedReadingOrder(f, "a", name = "Hidden", isPrivate = false, bookIds = listOf("priv"))

                    service(this@withSqlDatabase).actAs("b").discoverReadingOrders(limit = 1).value() shouldHaveSize 1
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .discoverReadingOrders(limit = 50)
                        .value()
                        .map { it.readingOrder.name } shouldContainExactlyInAnyOrder listOf("Shared Order", "More")
                }
            }
        }

        test("revoking a share hides the previously-visible book on the next getReadingOrder") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (orderS, _) = seedBaseFixture(f)
                    f.grantRepo.upsert(share("share-1", "priv-col", "b", SharePermission.Read))
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .getReadingOrder(orderS)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")

                    f.grantRepo.softDeleteGrant("priv-col", "b")
                    val after = service(this@withSqlDatabase).actAs("b").getReadingOrder(orderS).value()
                    after.bookCount shouldBe 2
                    after.books.map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }
    })

private data class ReadingOrderFixtures(
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val readingOrderBookRepo: ReadingOrderBookRepository,
    val policy: BookAccessPolicy,
)

private fun collectionFixture(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun share(
    id: String,
    collectionId: String,
    userId: String,
    permission: SharePermission,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = userId,
        sharedByUserId = "stranger",
        permission = permission,
        revision = 0L,
        updatedAt = 0L,
    )
