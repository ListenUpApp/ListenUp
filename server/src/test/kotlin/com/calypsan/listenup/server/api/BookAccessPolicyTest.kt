@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

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
        fun Database.fixture(): Fixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Fixture(
                collectionRepo = CollectionRepository(db = this, bus = bus, registry = registry),
                collectionBookRepo = CollectionBookRepository(db = this, bus = bus, registry = registry),
                shareRepo = CollectionShareRepository(db = this, bus = bus, registry = registry),
                policy = BookAccessPolicy(this),
            )
        }

        test("admin sees every book (incl. inbox + private)") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("public-book")
                seedTestBook("private-book")
                seedTestBook("inbox-book")
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

        test("uncollected book is public (any member sees it)") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("loose-book")
                val f = fixture()
                runTest {
                    f.policy.canAccess("anyone", UserRole.MEMBER, "loose-book") shouldBe true
                }
            }
        }

        test("owner sees books in their collection") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("owned-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "owned-book"))

                    f.policy.canAccess("owner", UserRole.MEMBER, "owned-book") shouldBe true
                }
            }
        }

        test("active read-share sees books in the shared collection") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("recipient")
                seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.shareRepo.upsert(share("s1", "col1", "recipient", SharePermission.Read))

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe true
                }
            }
        }

        test("active write-share sees books") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("recipient")
                seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.shareRepo.upsert(share("s1", "col1", "recipient", SharePermission.Write))

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe true
                }
            }
        }

        test("revoked (soft-deleted) share hides the book again") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("recipient")
                seedTestBook("shared-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "shared-book"))
                    f.shareRepo.upsert(share("s1", "col1", "recipient", SharePermission.Read))
                    f.shareRepo.softDeleteShare("col1", "recipient")

                    f.policy.canAccess("recipient", UserRole.MEMBER, "shared-book") shouldBe false
                }
            }
        }

        test("global-access collection's books are visible to all members") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("global-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner", isGlobalAccess = true))
                    f.collectionBookRepo.upsert(membership("col1", "global-book"))

                    // A member with no share and no ownership still sees it.
                    f.policy.canAccess("stranger", UserRole.MEMBER, "global-book") shouldBe true
                }
            }
        }

        test("book in a private collection (no relationship) is DENIED") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("col1", "private-book"))

                    f.policy.canAccess("stranger", UserRole.MEMBER, "private-book") shouldBe false
                }
            }
        }

        test("book in BOTH a private and an accessible collection → ALLOW (≥1 accessible)") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("both-book")
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
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("book")
                val f = fixture()
                runTest {
                    // Owner's membership is the only one, and it's tombstoned → the book is
                    // no longer in any live collection, so it becomes a public (uncollected) book.
                    // To prove the membership itself is ignored, the book also lives in a private
                    // collection owned by a stranger via a LIVE membership.
                    f.collectionRepo.upsert(collectionFixture("owned-col", owner = "me"))
                    f.collectionBookRepo.upsert(membership("owned-col", "book"))
                    f.collectionBookRepo.softDelete("owned-col", "book")
                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "book"))

                    // "me" only ever had a tombstoned membership; the live membership is in a
                    // private stranger-owned collection → denied.
                    f.policy.canAccess("me", UserRole.MEMBER, "book") shouldBe false
                }
            }
        }

        test("soft-deleted book is never accessible (even to a member)") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestBook("gone-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("col1", owner = "owner", isGlobalAccess = true))
                    f.collectionBookRepo.upsert(membership("col1", "gone-book"))
                    softDeleteBook("gone-book")

                    f.policy.canAccess("owner", UserRole.MEMBER, "gone-book") shouldBe false
                }
            }
        }

        test("canAccess(b) ⇔ b ∈ accessibleBookIds(query) for a random fixture") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder()
                seedTestUser("u2")
                // Seed books spanning all six states for member u2.
                val owned = "owned-book"
                val shared = "shared-book"
                val global = "global-book"
                val private = "private-book"
                val uncollected = "uncollected-book"
                val inbox = "inbox-book"
                listOf(owned, shared, global, private, uncollected, inbox).forEach { seedTestBook(it) }
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collectionFixture("owned-col", owner = "u2"))
                    f.collectionBookRepo.upsert(membership("owned-col", owned))

                    f.collectionRepo.upsert(collectionFixture("shared-col", owner = "owner"))
                    f.collectionBookRepo.upsert(membership("shared-col", shared))
                    f.shareRepo.upsert(share("s1", "shared-col", "u2", SharePermission.Read))

                    f.collectionRepo.upsert(collectionFixture("global-col", owner = "owner", isGlobalAccess = true))
                    f.collectionBookRepo.upsert(membership("global-col", global))

                    f.collectionRepo.upsert(collectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", private))

                    f.collectionRepo.upsert(collectionFixture("inbox-col", owner = "stranger", isInbox = true))
                    f.collectionBookRepo.upsert(membership("inbox-col", inbox))

                    // uncollected stays in no collection.

                    val accessibleIds =
                        f.policy.accessibleBookIds("u2", UserRole.MEMBER)
                            ?: error("expected a constrained id set for a member")
                    listOf(owned, shared, global, private, uncollected, inbox).forEach { bookId ->
                        f.policy.canAccess("u2", UserRole.MEMBER, bookId) shouldBe (bookId in accessibleIds)
                    }
                }
            }
        }
    })

private data class Fixture(
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val shareRepo: CollectionShareRepository,
    val policy: BookAccessPolicy,
)

private fun collectionFixture(
    id: String,
    owner: String,
    isInbox: Boolean = false,
    isGlobalAccess: Boolean = false,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = isInbox,
        isGlobalAccess = isGlobalAccess,
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
        sharedByUserId = "owner",
        permission = permission,
        revision = 0L,
        updatedAt = 0L,
    )

/** Soft-deletes [bookId] by stamping `deleted_at`, mirroring the syncable tombstone shape. */
private fun Database.softDeleteBook(bookId: String) {
    transaction(this) {
        BookTable.update({ BookTable.id eq bookId }) { it[BookTable.deletedAt] = System.currentTimeMillis() }
    }
}
