@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.konsist.stripComments
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.CollectionAccessHarness
import com.calypsan.listenup.server.testing.actAs
import com.calypsan.listenup.server.testing.collectionAccessHarness
import com.calypsan.listenup.server.testing.grantAllBooks
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The **emission-contract guard** for the per-user `AccessChanged` fan-out. Every visibility-changing
 * collection mutation must nudge **exactly** the set of users whose access it changed — no member
 * missed (they'd be stranded with stale visibility until the next foreground reconcile), no
 * [SYSTEM_OWNER_ID] noise (the ALL_BOOKS/INBOX sentinel is not a real client). Each case computes its
 * expected recipient set **before** the mutation and asserts the captured [SyncControl.AccessChanged]
 * frames equal it exactly.
 *
 * `deleteCollection` is the S2 money-shot: its audience must be captured while the grants are still
 * live (before the cascade tombstones them), so a member who reached the books *only* through the
 * deleted collection is told to re-derive in real time. Moving that nudge after the grant tombstone
 * drops the audience — this guard fires.
 *
 * A Konsist **completeness backstop** (bottom) proves the case list is exhaustive: every
 * `CollectionServiceImpl` function that mutates `collection_books` / grants must appear as a case
 * here or be allowlisted with a reason, so a new membership mutation can't ship without an emission
 * contract.
 */
class CollectionAccessEmissionContractTest :
    FunSpec({

        /** Seeds an ADMIN `admin` plus each of [memberIds] as a MEMBER. */
        fun ListenUpDatabase.seedAdminAndMembers(vararg memberIds: String) {
            seedTestUser("admin", UserRoleColumn.ADMIN)
            memberIds.forEach { seedTestUser(it) }
        }

        /** Materialises the library's ALL_BOOKS system collection and returns its id. */
        suspend fun CollectionServiceImpl.allBooksId(): String {
            val result = getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
            return (result as AppResult.Success).data.id.value
        }

        /** Creates a normal collection named [name] and returns its id. */
        suspend fun CollectionServiceImpl.newCollection(name: String): CollectionId {
            val result = createCollection("test-library", name)
            return (result as AppResult.Success).data.id
        }

        /** A live USER read grant for [userId] on [collectionId]. */
        suspend fun CollectionAccessHarness.shareTo(
            collectionId: String,
            userId: String,
        ) {
            grantRepo.upsert(
                CollectionShareSyncPayload(
                    id = "grant-$collectionId-$userId",
                    collectionId = collectionId,
                    sharedWithUserId = userId,
                    sharedByUserId = "admin",
                    permission = SharePermission.Read,
                    revision = 0L,
                    updatedAt = 0L,
                    deletedAt = null,
                ),
            )
        }

        // ── addBookToCollection: target audience + the ALL_BOOKS grant-holders the flip-out reaches ──
        test("addBookToCollection nudges the target audience AND the ALL_BOOKS holders it flips the book away from") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s1", "m1", "m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val allBooks = admin.allBooksId()
                    admin.addBookToCollection(CollectionId(allBooks), BookId("B"))
                    h.grantAllBooks(allBooks, "m1")
                    h.grantAllBooks(allBooks, "m2")
                    val c = admin.newCollection("C")
                    h.shareTo(c.value, "s1")

                    h.revisionTouch.touched.clear()
                    val recipients = captureAccessChanged(h.bus) { admin.addBookToCollection(c, BookId("B")) }

                    recipients shouldBe setOf("admin", "s1", "m1", "m2")
                    h.revisionTouch.touched shouldContain "B"
                }
            }
        }

        // ── removeBookFromCollection: target audience + ALL_BOOKS holders the flip-back reaches ──
        test("removeBookFromCollection nudges the target audience AND the ALL_BOOKS holders it flips the book back to") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s1", "m1", "m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val allBooks = admin.allBooksId()
                    h.grantAllBooks(allBooks, "m1")
                    h.grantAllBooks(allBooks, "m2")
                    val c = admin.newCollection("C")
                    h.shareTo(c.value, "s1")
                    admin.addBookToCollection(c, BookId("B")) // B now curated out of ALL_BOOKS

                    h.revisionTouch.touched.clear()
                    val recipients = captureAccessChanged(h.bus) { admin.removeBookFromCollection(c, BookId("B")) }

                    recipients shouldBe setOf("admin", "s1", "m1", "m2")
                    h.revisionTouch.touched shouldContain "B"
                }
            }
        }

        // ── setBookCollections: the union of the added and removed audiences ──
        test("setBookCollections nudges the union of the added and removed collection audiences") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s1", "s2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val c1 = admin.newCollection("C1")
                    val c2 = admin.newCollection("C2")
                    h.shareTo(c1.value, "s1")
                    h.shareTo(c2.value, "s2")
                    admin.addBookToCollection(c1, BookId("B"))

                    h.revisionTouch.touched.clear()
                    val recipients = captureAccessChanged(h.bus) { admin.setBookCollections(BookId("B"), listOf(c2)) }

                    recipients shouldBe setOf("admin", "s1", "s2")
                    h.revisionTouch.touched shouldContain "B"
                }
            }
        }

        // ── shareCollection: only the newly-shared user ──
        test("shareCollection nudges only the newly-shared user") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s3")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val c = admin.newCollection("C")

                    val recipients = captureAccessChanged(h.bus) { admin.shareCollection(c, "s3", SharePermission.Read) }

                    recipients shouldBe setOf("s3")
                }
            }
        }

        // ── updateShare: only the affected recipient ──
        test("updateShare nudges only the recipient whose permission changed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s4")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val c = admin.newCollection("C")
                    admin.shareCollection(c, "s4", SharePermission.Read)

                    val recipients = captureAccessChanged(h.bus) { admin.updateShare(c, "s4", SharePermission.Write) }

                    recipients shouldBe setOf("s4")
                }
            }
        }

        // ── revokeShare: only the ex-target, and only because a live grant existed ──
        test("revokeShare nudges only the ex-target when a live grant existed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s5")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val c = admin.newCollection("C")
                    admin.shareCollection(c, "s5", SharePermission.Read)

                    val recipients = captureAccessChanged(h.bus) { admin.revokeShare(c, "s5") }

                    recipients shouldBe setOf("s5")
                }
            }
        }

        // ── deleteCollection (S2): audience captured BEFORE grants tombstone + ALL_BOOKS re-home holders ──
        test("deleteCollection nudges the pre-delete audience (S2) AND the ALL_BOOKS holders a re-homed book returns to") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s6", "m1", "m2")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val allBooks = admin.allBooksId()
                    h.grantAllBooks(allBooks, "m1")
                    h.grantAllBooks(allBooks, "m2")
                    val c = admin.newCollection("C")
                    h.shareTo(c.value, "s6")
                    admin.addBookToCollection(c, BookId("B")) // sole membership → will re-home to ALL_BOOKS

                    h.revisionTouch.touched.clear()
                    val recipients = captureAccessChanged(h.bus) { admin.deleteCollection(c) }

                    // s6 is present ONLY because the audience was captured while the grant was still live (S2).
                    recipients shouldBe setOf("admin", "s6", "m1", "m2")
                    h.revisionTouch.touched shouldContain "B"
                }
            }
        }

        // ── releaseBooks: every target-collection audience + ALL_BOOKS holders for the unsorted release ──
        test("releaseBooks nudges every target audience and the ALL_BOOKS holders for the unsorted release") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedAdminAndMembers("s7", "m1", "m2")
                sql.seedTestBook("B")
                sql.seedTestBook("U")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)
                    val allBooks = admin.allBooksId()
                    h.grantAllBooks(allBooks, "m1")
                    h.grantAllBooks(allBooks, "m2")
                    admin.getOrCreateInbox("test-library")
                    admin.addToInbox("B", "test-library")
                    admin.addToInbox("U", "test-library")
                    val c = admin.newCollection("C")
                    h.shareTo(c.value, "s7")

                    h.revisionTouch.touched.clear()
                    val recipients =
                        captureAccessChanged(h.bus) {
                            admin.releaseBooks("test-library", mapOf("B" to listOf(c.value), "U" to emptyList()))
                        }

                    recipients shouldBe setOf("admin", "s7", "m1", "m2")
                    h.revisionTouch.touched shouldContain "B"
                    h.revisionTouch.touched shouldContain "U"
                }
            }
        }

        // ── Completeness backstop: no membership/grant mutation escapes an emission case ──
        test("every CollectionServiceImpl membership/grant mutation has an emission case (or an allowlist reason)") {
            val serviceFns =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { it.name == "CollectionServiceImpl" }
                    .flatMap { it.functions() }
            require(serviceFns.isNotEmpty()) { "CollectionServiceImpl not found in production scope — check the Konsist scope" }

            val uncovered =
                serviceFns
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        "collectionBookRepo.upsert(" in body ||
                            "collectionBookRepo.softDelete" in body ||
                            "grantRepo.upsert(" in body ||
                            "grantRepo.softDelete" in body
                    }.map { it.name }
                    .distinct()
                    .filterNot { it in EMISSION_CASE_FUNCTIONS || it in EMISSION_ALLOWLIST }
                    .map { "$it mutates collection_books/grants but has no emission contract case — add a case or allowlist it" }
            uncovered.shouldBeEmpty()
        }
    }) {
    companion object {
        /** The membership/grant mutations whose AccessChanged fan-out is pinned by a case above. */
        val EMISSION_CASE_FUNCTIONS =
            setOf(
                "addBookToCollection",
                "removeBookFromCollection",
                "setBookCollections",
                "shareCollection",
                "updateShare",
                "revokeShare",
                "deleteCollection",
                "releaseBooks",
            )

        /**
         * Functions that touch the mutation tokens but legitimately emit nothing here:
         *  - `reconcileSystemMembership` — the private flip helper, exercised through the add/remove cases.
         *  - `addToInbox` — quarantine by placement; hidden via delivery-time `canAccess`, no proactive nudge.
         */
        val EMISSION_ALLOWLIST = setOf("reconcileSystemMembership", "addToInbox")
    }
}

/**
 * Subscribes to [bus]'s control channel, runs [action], and returns the set of user ids that received
 * an [SyncControl.AccessChanged] frame. The collector starts UNDISPATCHED so it is subscribed before
 * [action] emits (the control channel has no replay); [runCurrent] then drains the buffered frames.
 */
private suspend fun TestScope.captureAccessChanged(
    bus: ChangeBus,
    action: suspend () -> Unit,
): Set<String> {
    val recipients = mutableListOf<String>()
    val collector =
        launch(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribeControl().collect { frame ->
                if (frame.control is SyncControl.AccessChanged) recipients += frame.userId
            }
        }
    action()
    runCurrent()
    collector.cancel()
    return recipients.toSet()
}
