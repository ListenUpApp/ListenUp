@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.testing.AccessModelOracle
import com.calypsan.listenup.server.testing.CollectionAccessHarness
import com.calypsan.listenup.server.testing.actAs
import com.calypsan.listenup.server.testing.collectionAccessHarness
import com.calypsan.listenup.server.testing.grantAllBooks
import com.calypsan.listenup.server.testing.junctionDiagnostic
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The **access-seam invariant matrix** — the guard that would have caught #680/#730.
 *
 * Every row asserts the user-visible access *invariant* through the single funnel every byte and
 * sync surface routes through — [BookAccessPolicy.canAccess] / [BookAccessPolicy.accessibleBookIds]
 * and the access-filtered `pullSince` / `digest` — **never** through a `collection_books` junction
 * read. That inversion is the whole point: #680/#730 slipped because the tests pinned junction state
 * (the mechanism), which is legitimately rewritten during a refactor, while the invariant (what a
 * member can actually reach) broke silently. Here a junction read is at most a `// mechanism
 * diagnostic` that makes a failure legible; weakening any row means editing a line that reads as an
 * access-control decision, cross-checked against the independent [AccessModelOracle].
 *
 * Rows:
 *  - **I1** exclusivity — curating a book into a real collection removes it from ALL_BOOKS (the #680 hole).
 *  - **I2** union — a book stays visible while any reachable containing collection is granted.
 *  - **I3** removal — a soft-deleted book is denied to member AND root; the tombstone is delivered.
 *  - **I4** inbox — a quarantined book is hidden from members, visible to root, released back into reach.
 *  - **I5** ALL_BOOKS grant — revoking one member's default grant hides uncollected books from them only.
 *  - **I6** dead relationships grant nothing — a tombstoned junction / collection / grant each deny.
 *  - **I7** deleteCollection — sole membership returns to everyone; multi keeps the surviving audience.
 *  - **I8** route parity — the HTTP surfaces funnel through the same policy; pinned by the existing
 *    `SeamLeakE2ETest` + `BookRoutesTest` rather than re-derived here (see the note at the tail).
 */
class AccessInvariantMatrixTest :
    FunSpec({

        /** The member's access-filtered live book-id set — the ids a real catch-up would deliver. */
        suspend fun CollectionAccessHarness.visibleLiveBooks(userId: String): List<String> {
            val extra: SqlFragment? = bookAccessPolicy.accessibleBookIdsSql(userId, UserRole.MEMBER)
            val page = bookRepo.pullSince(userId, cursor = 0L, limit = 1000, extraWhere = extra)
            return page.items.filter { it.deletedAt == null }.map { it.id }
        }

        /** Creates a collection owned by [ownerId] (create is not admin-gated; owner = the caller). */
        suspend fun CollectionServiceImpl.createCollectionAsOwner(
            ownerId: String,
            name: String,
        ): CollectionId {
            val created = actAs(ownerId).createCollection("test-library", name)
            require(created is AppResult.Success) { "createCollection failed for $ownerId: $created" }
            return created.data.id
        }

        // ─────────────────────────────── I1: exclusivity ───────────────────────────────
        test("I1 exclusivity: curating a book into a real collection denies it to a non-member (the #680 hole)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    admin.addBookToCollection(CollectionId(allBooks.data.id.value), BookId("B"))
                    h.grantAllBooks(allBooks.data.id.value, "m")

                    // Precondition: m reaches B via the default ALL_BOOKS grant (oracle + policy agree).
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                    h.visibleLiveBooks("m") shouldContain "B"

                    // Curate B into a private collection C that m is NOT a member of.
                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)

                    // THE INVARIANT: B left ALL_BOOKS → m can no longer reach it, via the funnel AND catch-up.
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                    h.visibleLiveBooks("m") shouldNotContain "B"
                    // mechanism diagnostic — the junction moved, but it is NOT what this row asserts.
                    h.junctionDiagnostic("B") shouldNotContain allBooks.data.id.value
                }
            }
        }

        // ─────────────────────────────── I2: union ───────────────────────────────
        test("I2 union: granting a containing collection restores access; revoking removes it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B"))

                    // No grant → invisible.
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()

                    // Grant C to m → visible (union: any one reachable containing collection wins).
                    val grantId = "grant-c-m"
                    h.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = grantId,
                            collectionId = c.data.id.value,
                            sharedWithUserId = "m",
                            sharedByUserId = "admin",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                    h.visibleLiveBooks("m") shouldContain "B"

                    // Revoke → invisible again.
                    h.grantRepo.softDelete(grantId, clientOpId = null)
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                }
            }
        }

        // ─────────────────────────────── I3: removal ───────────────────────────────
        test("I3 removal: a soft-deleted book is denied to member AND root, and the tombstone is delivered") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    admin.addBookToCollection(CollectionId(allBooks.data.id.value), BookId("B"))
                    h.grantAllBooks(allBooks.data.id.value, "m")
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()

                    // Remove the book through the real removal cascade.
                    h.bookRepo.softDelete(BookId("B"), clientOpId = null) shouldBe AppResult.Success(Unit)

                    // Denied to the member (oracle agrees) AND to root — bookExists requires deleted_at IS NULL.
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                    h.bookAccessPolicy.canAccess("root", UserRole.ROOT, "B").shouldBeFalse()

                    // Catch-up delivers the tombstone (so the member converges), but no LIVE row remains;
                    // the digest excludes it going forward.
                    val extra = h.bookAccessPolicy.accessibleBookIdsSql("m", UserRole.MEMBER)
                    val page = h.bookRepo.pullSince("m", cursor = 0L, limit = 1000, extraWhere = extra)
                    page.items.filter { it.deletedAt == null }.map { it.id } shouldNotContain "B"
                    page.items.any { it.id == "B" && it.deletedAt != null }.shouldBeTrue()
                    h.bookRepo.digest("m", cursor = 1_000_000L, extraWhere = extra).count shouldBe 0
                }
            }
        }

        // ─────────────────────────────── I4: inbox ───────────────────────────────
        test("I4 inbox: a quarantined book is hidden from members, visible to root, and reachable once released") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    admin.getOrCreateInbox("test-library")
                    admin.addToInbox("B", "test-library") shouldBe AppResult.Success(Unit)

                    // In INBOX (not ALL_BOOKS) → hidden from a member, visible to root.
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                    h.bookAccessPolicy.canAccess("root", UserRole.ROOT, "B").shouldBeTrue()

                    // Release into a collection m is granted → reachable.
                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    h.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "grant-c-m",
                            collectionId = c.data.id.value,
                            sharedWithUserId = "m",
                            sharedByUserId = "admin",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    admin.releaseBooks("test-library", mapOf("B" to listOf(c.data.id.value))) shouldBe AppResult.Success(Unit)

                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m", listOf("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }

        // ─────────────────────────────── I5: ALL_BOOKS grant ───────────────────────────────
        test("I5 ALL_BOOKS grant: revoking one member's default grant hides uncollected books from them only") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m1")
                sql.seedTestUser("m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    admin.addBookToCollection(CollectionId(allBooks.data.id.value), BookId("B"))
                    h.grantAllBooks(allBooks.data.id.value, "m1")
                    h.grantAllBooks(allBooks.data.id.value, "m2")
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeTrue()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeTrue()

                    // Revoke m1's ALL_BOOKS grant only.
                    h.grantRepo.softDelete("grant-${allBooks.data.id.value}-m1", clientOpId = null)

                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m1", listOf("B"))
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m2", listOf("B"))
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeFalse()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }

        // ─────────────────────────────── I6: dead relationships grant nothing ───────────────────────────────
        test("I6 dead relationships grant nothing: a tombstoned junction, collection, or grant each deny") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("Bj") // dead junction
                sql.seedTestBook("Bc") // dead collection
                sql.seedTestBook("Bg") // dead grant
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    // (a) tombstoned junction: m owns C1, Bj was in it, junction tombstoned → deny.
                    val c1 = admin.createCollectionAsOwner("m", "C1")
                    admin.addBookToCollection(c1, BookId("Bj"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bj").shouldBeTrue() // control
                    h.collectionBookRepo.softDelete(collectionId = c1.value, bookId = "Bj")
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bj").shouldBeFalse()

                    // (b) tombstoned collection: m owns C2 holding Bc (live junction), but C2 is tombstoned → deny.
                    val c2 = admin.createCollectionAsOwner("m", "C2")
                    admin.addBookToCollection(c2, BookId("Bc"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bc").shouldBeTrue() // control
                    h.collectionRepo.softDelete(c2.value, clientOpId = null)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bc").shouldBeFalse()

                    // (c) tombstoned grant: stranger owns C3 holding Bg; m's grant on C3 tombstoned → deny.
                    val c3 = admin.createCollectionAsOwner("stranger", "C3")
                    admin.addBookToCollection(c3, BookId("Bg"))
                    h.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "grant-c3-m",
                            collectionId = c3.value,
                            sharedWithUserId = "m",
                            sharedByUserId = "stranger",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bg").shouldBeTrue() // control
                    h.grantRepo.softDelete("grant-c3-m", clientOpId = null)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "Bg").shouldBeFalse()

                    AccessModelOracle.assertAccessInvariants(
                        h.bookAccessPolicy,
                        h.db,
                        h.driver,
                        "m",
                        listOf("Bj", "Bc", "Bg"),
                    )
                }
            }
        }

        // ─────────────────────────────── I7: deleteCollection ───────────────────────────────
        test("I7 deleteCollection sole membership: the book returns to everyone via ALL_BOOKS") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m1")
                sql.seedTestUser("m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    // Establish ALL_BOOKS + both members' default grants first.
                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    h.grantAllBooks(allBooks.data.id.value, "m1")
                    h.grantAllBooks(allBooks.data.id.value, "m2")

                    // B lives ONLY in C (m1 granted). It left ALL_BOOKS, so m2 (only ALL_BOOKS) can't see it.
                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B"))
                    h.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "grant-c-m1",
                            collectionId = c.data.id.value,
                            sharedWithUserId = "m1",
                            sharedByUserId = "admin",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeTrue()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeFalse()

                    // Delete C (B's only real membership) → B returns to ALL_BOOKS → visible to EVERYONE.
                    admin.deleteCollection(c.data.id) shouldBe AppResult.Success(Unit)
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m1", listOf("B"))
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m2", listOf("B"))
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeTrue()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }

        test("I7 deleteCollection multi membership: the book drops only for the deleted collection's audience") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m1")
                sql.seedTestUser("m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    // B in C1 (m1 granted) and C2 (m2 granted). Union → both see it.
                    val c1 = admin.createCollection("test-library", "C1")
                    val c2 = admin.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success && c2 is AppResult.Success)
                    admin.addBookToCollection(c1.data.id, BookId("B"))
                    admin.addBookToCollection(c2.data.id, BookId("B"))
                    for ((cid, member) in listOf(c1.data.id.value to "m1", c2.data.id.value to "m2")) {
                        h.grantRepo.upsert(
                            CollectionShareSyncPayload(
                                id = "grant-$cid-$member",
                                collectionId = cid,
                                sharedWithUserId = member,
                                sharedByUserId = "admin",
                                permission = SharePermission.Read,
                                revision = 0L,
                                updatedAt = 0L,
                                deletedAt = null,
                            ),
                        )
                    }
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeTrue()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeTrue()

                    // Delete C1: B still lives in C2 (never returns to ALL_BOOKS) → only m1 loses it.
                    admin.deleteCollection(c1.data.id) shouldBe AppResult.Success(Unit)
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m1", listOf("B"))
                    AccessModelOracle.assertAccessInvariants(h.bookAccessPolicy, h.db, h.driver, "m2", listOf("B"))
                    h.bookAccessPolicy.canAccess("m1", UserRole.MEMBER, "B").shouldBeFalse()
                    h.bookAccessPolicy.canAccess("m2", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }

        // ─────────────────────────────── I8: route parity — pinned by existing E2E, not duplicated ───────────────────────────────
        // The HTTP byte/metadata surfaces all funnel through BookAccessPolicy.canAccess, which I1–I7
        // exercise directly. Proving the *routes actually call the funnel* needs the full module, and
        // that is already pinned comprehensively for a curated-out / private book by:
        //   • SeamLeakE2ETest — getBook, search, audio, cover, catch-up, digest, AND the SSE firehose,
        //     each with a visible public control, for a book unreachable to a member (the spec's SKIP
        //     note names this test as the route-level pin), and
        //   • BookRoutesTest — "GET /api/v1/books/{id} returns 404 when a member can't reach a private book".
        // A third full-module testApplication here would duplicate those (and is library-alignment
        // fragile: reconcile keys off books.library_id, so the book, ALL_BOOKS, and the member's default
        // grant must share one library — the exact trap SeamLeak's library-aware makeBookPublic avoids).
        // Per CLAUDE.md's no-redundant-coverage rule, I8 is deferred to those pins rather than re-derived.
    })
