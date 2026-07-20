@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BookAccessPolicy] — the single book-visibility predicate.
 *
 * Each test seeds a real in-memory database (library + books + collections +
 * memberships + shares) via the 1a repositories, then asserts [BookAccessPolicy.canAccess].
 * The final property test proves the equivalence the whole design rests on:
 * `canAccess(b)` is true exactly when `b` is in the set returned by
 * [BookAccessPolicy.accessibleBookIdsSql].
 */
class BookAccessPolicyTest :
    FunSpec({

        /** Wires the three 1a repos plus the policy under test against the receiver database. */
        fun SqlTestDatabases.fixture(): Fixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Fixture(
                collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                collectionBookRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        test("admin sees every book (incl. inbox + private)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                sql.seedTestBook("inbox-book")
                val f = fixture()
                runTest {
                    // private-book lives in a private collection owned by a stranger.
                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))
                    // inbox-book lives in an inbox collection.
                    f.collectionRepo.upsert(collectionFixture("inbox-col", owner = "stranger", isInbox = true))
                    f.collectionBookRepo.upsert(membership("inbox-col", "inbox-book"))

                    f.policy.canAccess("admin", UserRole.ADMIN, "public-book") shouldBe true
                    f.policy.canAccess("admin", UserRole.ADMIN, "private-book") shouldBe true
                    f.policy.canAccess("admin", UserRole.ADMIN, "inbox-book") shouldBe true
                    // admin filter is unconstrained.
                    f.policy.accessibleBookIdsSql("admin", UserRole.ADMIN) shouldBe null
                }
            }
        }

        test("book in NO collection is INVISIBLE to a member (pure union — no uncollected→public)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("loose-book")
                val f = fixture()
                runTest {
                    // Under pure union, a book in no reachable collection is invisible, period.
                    f.policy.canAccess("anyone", UserRole.MEMBER, "loose-book") shouldBe false
                }
            }
        }

        test("book in ALL_BOOKS is visible to a granted member") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                val f = fixture()
                runTest {
                    // ALL_BOOKS is the public substrate: a system collection every member holds a
                    // grant on. Membership in it + the member's grant = visibility under pure union.
                    f.collectionRepo.upsert(collectionFixture("all-books", owner = "system"))
                    f.collectionBookRepo.upsert(membership("all-books", "public-book"))
                    f.grantRepo.upsert(share("g1", "all-books", "member", SharePermission.Read))

                    f.policy.canAccess("member", UserRole.MEMBER, "public-book") shouldBe true
                }
            }
        }

        test("book in a collection the member neither owns nor is granted is INVISIBLE") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("walled-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("stranger-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("stranger-col", "walled-book"))

                    f.policy.canAccess("member", UserRole.MEMBER, "walled-book") shouldBe false
                }
            }
        }

        test("owner sees books in their collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("owned-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "owned-book"))

                    f.policy.canAccess("owner", UserRole.MEMBER, "owned-book") shouldBe true
                }
            }
        }

        test("active read-share sees books in the shared collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("recipient")
                sql.seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.grantRepo.upsert(share("s1", "col1", "recipient", SharePermission.Read))

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe true
                }
            }
        }

        test("active write-share sees books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("recipient")
                sql.seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.grantRepo.upsert(share("s1", "col1", "recipient", SharePermission.Write))

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe true
                }
            }
        }

        test("revoked (soft-deleted) share hides the book again") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("recipient")
                sql.seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.grantRepo.upsert(share("s1", "col1", "recipient", SharePermission.Read))
                    f.grantRepo.softDeleteGrant("col1", "recipient")

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe false
                }
            }
        }

        test("book in a private collection (no relationship) is DENIED") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "private-book"))

                    f.policy.canAccess("stranger", UserRole.MEMBER, "private-book") shouldBe false
                }
            }
        }

        test("book in BOTH a private and an accessible collection → ALLOW (≥1 accessible)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("both-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "both-book"))
                    f.collectionRepo.upsert(collectionFixture("owned-col", owner = "me"))
                    f.collectionBookRepo.upsert(membership("owned-col", "both-book"))

                    f.policy.canAccess("me", UserRole.MEMBER, "both-book") shouldBe true
                }
            }
        }

        test("soft-deleted collection membership does not grant access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book")
                val f = fixture()
                runTest {
                    // "me"'s membership in their own collection is tombstoned, so that branch no
                    // longer reaches "me". The only LIVE membership is in a private stranger-owned
                    // collection "me" neither owns nor is granted → under pure union, denied. This
                    // proves the tombstoned membership is ignored (it would otherwise grant access).
                    f.collectionRepo.upsert(collectionFixture("owned-col", owner = "me"))
                    f.collectionBookRepo.upsert(membership("owned-col", "book"))
                    f.collectionBookRepo.softDelete("owned-col", "book")
                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "book"))

                    f.policy.canAccess("me", UserRole.MEMBER, "book") shouldBe false
                }
            }
        }

        test("soft-deleted collection drops its membership (book has no live collection → INVISIBLE)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u2")
                sql.seedTestBook("book")
                val f = fixture()
                runTest {
                    // Book lives in exactly one collection — a private one owned by a stranger.
                    // Tombstoning the COLLECTION (not the membership, not a share) removes the
                    // only live membership: the `c.deleted_at IS NULL` guard excludes it from
                    // the union subquery, so `book` is in no live reachable collection. Under
                    // pure union that means INVISIBLE — there is no uncollected→public fallback.
                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "book"))
                    f.collectionRepo.softDelete("private-col")

                    f.policy.canAccess("u2", UserRole.MEMBER, "book") shouldBe false
                    // Equivalence holds on this branch too.
                    val accessibleIds =
                        f.policy.accessibleBookIds("u2", UserRole.MEMBER)
                            ?: error("expected a constrained id set for a member")
                    ("book" in accessibleIds) shouldBe false
                }
            }
        }

        test("soft-deleted book is never accessible (even to a member)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("gone-book")
                val f = fixture()
                runTest {
                    // Owned collection → the book would be visible to "owner" if it were live.
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "gone-book"))
                    softDeleteBook("gone-book")

                    f.policy.canAccess("owner", UserRole.MEMBER, "gone-book") shouldBe false
                }
            }
        }

        test("canAccess(b) ⇔ b ∈ accessibleBookIds(query) for a random fixture") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u2")
                // Seed books spanning the pure-union states for member u2: owned (visible),
                // shared (visible), public-via-ALL_BOOKS (visible), private (invisible),
                // uncollected (invisible), inbox (invisible).
                val owned = "owned-book"
                val shared = "shared-book"
                val public = "public-book"
                val private = "private-book"
                val uncollected = "uncollected-book"
                val inbox = "inbox-book"
                listOf(owned, shared, public, private, uncollected, inbox).forEach { sql.seedTestBook(it) }
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("owned-col", owner = "u2"))
                    f.collectionBookRepo.upsert(membership("owned-col", owned))

                    f.collectionRepo.upsert(collectionFixture("shared-col", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("shared-col", shared))
                    f.grantRepo.upsert(share("s1", "shared-col", "u2", SharePermission.Read))

                    // Public substrate: ALL_BOOKS membership + u2's grant.
                    f.collectionRepo.upsert(collectionFixture("all-books", owner = "system"))
                    f.collectionBookRepo.upsert(membership("all-books", public))
                    f.grantRepo.upsert(share("g1", "all-books", "u2", SharePermission.Read))

                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", private))

                    f.collectionRepo.upsert(collectionFixture("inbox-col", owner = "stranger", isInbox = true))
                    f.collectionBookRepo.upsert(membership("inbox-col", inbox))

                    // uncollected stays in no collection → invisible under pure union.

                    val accessibleIds =
                        f.policy.accessibleBookIds("u2", UserRole.MEMBER)
                            ?: error("expected a constrained id set for a member")
                    listOf(owned, shared, public, private, uncollected, inbox).forEach { bookId ->
                        f.policy.canAccess("u2", UserRole.MEMBER, bookId) shouldBe (bookId in accessibleIds)
                    }
                }
            }
        }
    })

private data class Fixture(
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val policy: BookAccessPolicy,
)

private fun collectionFixture(
    id: String,
    owner: String,
    isInbox: Boolean = false,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = isInbox,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "${collectionId}:${bookId}",
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
        sharedByUserId = "owner",
        permission = permission,
        revision = 0L,
        updatedAt = 0L,
    )

/** Soft-deletes [bookId] by stamping `deleted_at`, mirroring the syncable tombstone shape. */
private fun SqlTestDatabases.softDeleteBook(bookId: String) {
    val now = System.currentTimeMillis()
    sql.transaction {
        sql.booksQueries.softDeleteById(
            revision = now,
            updated_at = now,
            deleted_at = now,
            client_op_id = null,
            id = bookId,
        )
    }
}
