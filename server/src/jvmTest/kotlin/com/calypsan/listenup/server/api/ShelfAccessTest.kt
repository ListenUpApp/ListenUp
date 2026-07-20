@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
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
 * The access-hiding proof for [ShelfServiceImpl.getShelf] and
 * [ShelfServiceImpl.discoverShelves] — the headline requirement that a viewer is
 * never shown a book they cannot access, and is never even told a private shelf exists.
 *
 * Seeds a real in-memory database via the same collection/share fixtures
 * [BookAccessPolicyTest] uses, builds shelves through the real repositories, then
 * asserts the *exact* visible book sets and counts for each caller — owner, admin,
 * and an unrelated MEMBER viewer. "Non-empty" proves nothing here; every assertion
 * pins the precise id set.
 *
 * Fixture, shared across the suite:
 * - **A** (`a`) — MEMBER, the shelf owner.
 * - **B** (`b`) — MEMBER, an unrelated viewer holding a default ALL_BOOKS grant.
 * - **pub** — in ALL_BOOKS (the public substrate) → visible to every granted member, incl. B.
 * - **priv** — in a private collection owned by a third party; B has no grant → invisible to B.
 * - **glob** — also in ALL_BOOKS → visible to B (name kept for historical continuity).
 * - **S** — A's PUBLIC shelf, books `[pub, priv, glob]`.
 * - **P** — A's PRIVATE shelf, books `[pub]`.
 */
class ShelfAccessTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        /** A service whose every collaborator is wired against [dbs]; bind a caller with [actAs]. */
        fun service(dbs: SqlTestDatabases): ShelfServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ShelfServiceImpl(
                shelfRepo = ShelfRepository(db = dbs.sql, bus = bus, registry = registry),
                shelfBookRepo = ShelfBookRepository(db = dbs.sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
                readAssembler = ShelfReadAssembler(dbs.sql),
                clock = fixedClock,
                principal = principalFor("a"),
            )
        }

        fun ShelfServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): ShelfServiceImpl = copyWith(principalFor(userId, role))

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /**
         * Wires the collection-side repositories needed to make `priv` genuinely
         * inaccessible to B and `glob` globally visible, plus a [ShelfBookRepository]
         * for seeding shelf membership directly (bypassing the owner-access gate that
         * would otherwise refuse to shelve a book the owner can't see).
         */
        fun fixtures(dbs: SqlTestDatabases): Fixtures {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Fixtures(
                collectionRepo =
                    CollectionRepository(
                        db = dbs.sql,
                        bus = bus,
                        registry = registry,
                        driver = dbs.driver,
                    ),
                collectionBookRepo =
                    CollectionBookRepository(
                        db = dbs.sql,
                        bus = bus,
                        registry = registry,
                        driver = dbs.driver,
                    ),
                grantRepo =
                    CollectionGrantRepository(
                        db = dbs.sql,
                        bus = bus,
                        registry = registry,
                        driver = dbs.driver,
                    ),
                shelfBookRepo = ShelfBookRepository(db = dbs.sql, bus = bus, registry = registry),
                policy = BookAccessPolicy(dbs.sql, dbs.driver),
            )
        }

        /**
         * Creates a shelf owned by [ownerId] via the real service (so id generation and
         * privacy flow through production code), then seeds its [bookIds] directly through
         * the substrate repo — bypassing the owner-access gate so an inaccessible book can
         * still sit on a shelf for the viewer-side filtering to hide.
         */
        suspend fun SqlTestDatabases.seedShelf(
            f: Fixtures,
            ownerId: String,
            name: String,
            isPrivate: Boolean,
            bookIds: List<String>,
        ): ShelfId {
            val shelf = service(this).actAs(ownerId).createShelf(name = name, isPrivate = isPrivate).value()
            bookIds.forEach { f.shelfBookRepo.addBook(shelf.id.value, it, userId = ownerId) }
            return shelf.id
        }

        /**
         * Seeds the shared fixture: books pub/priv/glob with their collection relationships,
         * and A's public shelf S `[pub, priv, glob]` + private shelf P `[pub]`.
         */
        suspend fun SqlTestDatabases.seedBaseFixture(f: Fixtures): Pair<ShelfId, ShelfId> {
            f.collectionRepo.upsert(collectionFixture("priv-col", owner = "stranger"))
            f.collectionBookRepo.upsert(membership("priv-col", "priv"))
            // pub + glob are public the new way: members of ALL_BOOKS with B granted on it.
            f.collectionRepo.upsert(collectionFixture("all-books", owner = "system"))
            f.collectionBookRepo.upsert(membership("all-books", "pub"))
            f.collectionBookRepo.upsert(membership("all-books", "glob"))
            f.grantRepo.upsert(share("all-books-grant-b", "all-books", "b", SharePermission.Read))

            val shelfS = seedShelf(f, "a", name = "Shared Picks", isPrivate = false, bookIds = listOf("pub", "priv", "glob"))
            val shelfP = seedShelf(f, "a", name = "Secret", isPrivate = true, bookIds = listOf("pub"))
            return shelfS to shelfP
        }

        // ── 1 + 2 + 3: getShelf access by caller role ──────────────────────────

        test("non-owner sees a public shelf access-filtered to only visible books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (shelfS, _) = seedBaseFixture(f)
                    // Sanity: priv really is invisible to B; pub & glob really are visible.
                    f.policy.canAccess("b", UserRole.MEMBER, "priv") shouldBe false
                    f.policy.canAccess("b", UserRole.MEMBER, "pub") shouldBe true
                    f.policy.canAccess("b", UserRole.MEMBER, "glob") shouldBe true

                    val detail = service(this@withSqlDatabase).actAs("b").getShelf(shelfS).value()
                    detail.isOwner shouldBe false
                    detail.bookCount shouldBe 2
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }

        test("owner sees every book on their public shelf, including ones they can't access via collections") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (shelfS, _) = seedBaseFixture(f)
                    // Owner A is a MEMBER and cannot access priv via collections — owner bypass
                    // means they still see every book on their own shelf.
                    f.policy.canAccess("a", UserRole.MEMBER, "priv") shouldBe false

                    val detail = service(this@withSqlDatabase).actAs("a").getShelf(shelfS).value()
                    detail.isOwner shouldBe true
                    detail.bookCount shouldBe 3
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")
                }
            }
        }

        test("admin sees every book on a non-owned public shelf") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (shelfS, _) = seedBaseFixture(f)

                    val detail = service(this@withSqlDatabase).actAs("b", UserRole.ADMIN).getShelf(shelfS).value()
                    detail.isOwner shouldBe false
                    detail.bookCount shouldBe 3
                    detail.books.map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")
                }
            }
        }

        // ── 4: private shelf invisible to a non-owner ──────────────────────────

        test("a non-owner gets NotFound (never Forbidden, never an empty shelf) on a private shelf") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (_, shelfP) = seedBaseFixture(f)

                    val result = service(this@withSqlDatabase).actAs("b").getShelf(shelfP)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.NotFound>()
                }
            }
        }

        // ── 5: discovery filters books + excludes private + excludes own shelves ──

        test("discovery filters another user's public shelf and excludes private + own shelves") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (shelfS, _) = seedBaseFixture(f)
                    // B owns a public shelf of their own — it must be excluded from B's discovery.
                    seedShelf(f, "b", name = "My Own", isPrivate = false, bookIds = listOf("pub"))

                    val discovered = service(this@withSqlDatabase).actAs("b").discoverShelves().value()

                    discovered shouldHaveSize 1
                    val s = discovered.first()
                    s.shelf.id shouldBe shelfS
                    s.ownerId shouldBe "a"
                    s.ownerDisplayName shouldBe "a"
                    s.shelf.bookCount shouldBe 2
                    // Discovery returns the summary (book ids live in getShelf); prove the
                    // filtered detail of the discovered shelf is exactly [pub, glob].
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .getShelf(s.shelf.id)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }

        // ── 6: discovery excludes a shelf with zero accessible books ───────────

        test("discovery excludes a public shelf whose only books are all inaccessible") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    seedBaseFixture(f)
                    // A second public shelf X whose ONLY book is priv (invisible to B).
                    seedShelf(f, "a", name = "All Hidden", isPrivate = false, bookIds = listOf("priv"))

                    val discovered = service(this@withSqlDatabase).actAs("b").discoverShelves().value()

                    // Only S survives; X (zero accessible books) is excluded — no empty teaser.
                    discovered shouldHaveSize 1
                    discovered.first().shelf.name shouldBe "Shared Picks"
                }
            }
        }

        // ── 7: limit counts visible shelves, applied after access exclusion ────

        test("discoverShelves(limit) returns exactly limit visible shelves") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    seedBaseFixture(f) // S is discoverable (has pub, glob)
                    // A second discoverable public shelf, plus one all-inaccessible shelf that
                    // must NOT consume a limit slot.
                    seedShelf(f, "a", name = "More", isPrivate = false, bookIds = listOf("glob"))
                    seedShelf(f, "a", name = "Hidden", isPrivate = false, bookIds = listOf("priv"))

                    // Two shelves are discoverable; limit 1 → exactly 1 returned.
                    service(this@withSqlDatabase).actAs("b").discoverShelves(limit = 1).value() shouldHaveSize 1
                    // No limit pressure → both discoverable shelves, never the all-hidden one.
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .discoverShelves(limit = 50)
                        .value()
                        .map { it.shelf.name } shouldContainExactlyInAnyOrder listOf("Shared Picks", "More")
                }
            }
        }

        // ── 8: access revocation reflected on the next read ────────────────────

        test("revoking a share hides the previously-visible book on the next getShelf") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("a")
                sql.seedTestUser("b")
                listOf("pub", "priv", "glob").forEach { sql.seedTestBook(it) }
                val f = fixtures(this)
                runTest {
                    val (shelfS, _) = seedBaseFixture(f)
                    // Grant B a read-share into priv's collection: B can now see priv.
                    f.grantRepo.upsert(share("share-1", "priv-col", "b", SharePermission.Read))
                    service(this@withSqlDatabase)
                        .actAs("b")
                        .getShelf(shelfS)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("pub", "priv", "glob")

                    // Revoke the share; the next read must drop priv.
                    f.grantRepo.softDeleteGrant("priv-col", "b")
                    val after = service(this@withSqlDatabase).actAs("b").getShelf(shelfS).value()
                    after.bookCount shouldBe 2
                    after.books.map { it.bookId } shouldContainExactly listOf("pub", "glob")
                }
            }
        }
    })

private data class Fixtures(
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val shelfBookRepo: ShelfBookRepository,
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
        id = "$collectionId:$bookId",
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
