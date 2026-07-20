@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Reproduction + regression proof for plan 087: a tombstone delivered on the access-filtered pull
 * path ([SqlSyncableRepository.pullSince] / [pullByIds]) must carry identity + sync-discipline
 * fields ONLY — never the deleted row's content. The catch-up path deletes-ungated so every client
 * converges (a member must learn to drop a row it could never access), but the payload it ships for
 * that tombstone must be empty of content: the live firehose's `SyncEvent.Deleted` carries none.
 *
 * Before the fix, `readPayloads` hydrated the full deleted row, so a member's `since=0` pull leaked
 * private book titles, folder `rootPath`s (absolute server paths), and roster emails/roles. Each
 * test asserts BOTH that the tombstone still arrives (convergence) and that its content is blanked.
 *
 * Fixture wiring mirrors [BookCatchUpAccessTest] / [ActivityCatchUpAccessTest] /
 * [LibraryFolderSyncAccessTest] in this directory.
 */
class TombstonePayloadMinimizationTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): TombstoneFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db = sql, bus = bus, registry = registry)
            val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
            return TombstoneFixture(
                bookRepo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = GenreRepository(db = sql, bus = bus, registry = registry),
                    ),
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
                grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
                activityRepo = ActivitySyncRepository(db = sql, bus = bus, registry = registry, driver = driver),
                folderRepo = LibraryFolderRepository(db = sql, bus = bus, registry = registry, driver = driver),
                rosterRepo = AdminUserRosterRepository(db = sql, bus = bus, registry = registry, driver = driver),
                policy = BookAccessPolicy(sql, driver),
            )
        }

        test("books member pullSince delivers a private-book tombstone with blanked content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.makePublic("public-book", memberId = "member")
                    f.collectionRepo.upsert(collection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))
                    f.bookRepo.softDelete(BookId("private-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page = f.bookRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)

                    val tomb = page.items.firstOrNull { it.id == "private-book" }.shouldNotBeNull()
                    // Convergence contract: the tombstone arrives with identity + sync discipline.
                    tomb.deletedAt shouldNotBe null
                    (tomb.revision > 1L) shouldBe true
                    // Leak proof: every content field is blanked (was "Test Book private-book").
                    tomb.title shouldBe ""
                    tomb.description shouldBe null
                    tomb.rootRelPath shouldBe ""
                    tomb.contributors shouldBe emptyList()
                    tomb.series shouldBe emptyList()
                    tomb.chapters shouldBe emptyList()
                    tomb.audioFiles shouldBe emptyList()
                }
            }
        }

        test("books member pullByIds delivers a private-book tombstone with blanked content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("private-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))
                    f.bookRepo.softDelete(BookId("private-book"))

                    val extra = f.policy.accessibleBookIdsSql("member", UserRole.MEMBER)
                    val page =
                        f.bookRepo.pullByIds(
                            userId = "member",
                            matchColumn = "id",
                            matchValues = listOf("private-book"),
                            extraWhere = extra,
                        )

                    val tomb = page.items.firstOrNull { it.id == "private-book" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.title shouldBe ""
                    tomb.contributors shouldBe emptyList()
                }
            }
        }

        test("books admin pullSince (null filter) also minimizes the tombstone") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("doomed-book")
                val f = fixture()
                runTest {
                    f.bookRepo.softDelete(BookId("doomed-book"))

                    // Admin passes a null fragment → the ungated substrate path, which also returns tombstones.
                    val adminExtra = f.policy.accessibleBookIdsSql("admin", UserRole.ADMIN)
                    adminExtra shouldBe null
                    val page = f.bookRepo.pullSince("admin", cursor = 0L, limit = 100, extraWhere = adminExtra)

                    val tomb = page.items.firstOrNull { it.id == "doomed-book" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.title shouldBe ""
                }
            }
        }

        test("collections member pullSince delivers a stranger-collection tombstone with blanked content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "secret-plans",
                            libraryId = "test-library",
                            ownerId = "stranger",
                            name = "Secret Plans",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    f.collectionRepo.softDelete("secret-plans")

                    val extra = f.policy.accessibleCollectionIdsSql("member", UserRole.MEMBER)
                    val page = f.collectionRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)

                    val tomb = page.items.firstOrNull { it.id == "secret-plans" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.name shouldBe ""
                    tomb.ownerId shouldBe ""
                }
            }
        }

        test("collection_shares member pullSince delivers a foreign-grant tombstone with blanked content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestUser("stranger") // FK: collection_grants.principal_id → users(id)
                val f = fixture()
                runTest {
                    // FK: collection_grants.collection_id → collections(id). Owned by a stranger, so the
                    // grant is one the member can never see — only the tombstone reaches them.
                    f.collectionRepo.upsert(collection("some-collection", owner = "stranger"))
                    f.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "secret-grant",
                            collectionId = "some-collection",
                            sharedWithUserId = "stranger",
                            sharedByUserId = "system",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    f.grantRepo.softDelete("secret-grant")

                    val extra = f.policy.visibleCollectionGrantIdsSql("member", UserRole.MEMBER)
                    val page = f.grantRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)

                    val tomb = page.items.firstOrNull { it.id == "secret-grant" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.collectionId shouldBe ""
                    tomb.sharedWithUserId shouldBe ""
                    tomb.sharedByUserId shouldBe ""
                }
            }
        }

        test("activities member pullSince delivers an inaccessible-book activity tombstone with blanked content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.activityRepo.upsert(
                        ActivitySyncPayload(
                            id = "doomed-activity",
                            userId = "alice",
                            type = "FINISHED_BOOK",
                            bookId = "inaccessible-book",
                            isReread = false,
                            durationMs = 0L,
                            milestoneValue = 0,
                            milestoneUnit = null,
                            shelfId = null,
                            shelfName = null,
                            occurredAt = 0L,
                            revision = 0L,
                            createdAt = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    f.activityRepo.softDelete("doomed-activity")

                    val extra = activitiesAccessFilter(f.policy, "member", UserRole.MEMBER)
                    val page = f.activityRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)

                    val tomb = page.items.firstOrNull { it.id == "doomed-activity" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.type shouldBe ""
                    tomb.bookId shouldBe null
                    tomb.userId shouldBe ""
                }
            }
        }

        test("library_folders member pullSince delivers a folder tombstone with blanked rootPath") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.folderRepo.upsert(
                        LibraryFolderSyncPayload(
                            id = "secret-folder",
                            libraryId = "test-library",
                            rootPath = "/srv/secret/audiobooks",
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    f.folderRepo.softDelete(FolderId("secret-folder"))

                    // Mirrors LIBRARY_FOLDERS_HIDDEN in SyncRoutes (private there): members see no folder rows.
                    val hidden = SqlFragment(sql = "SELECT id FROM library_folders WHERE 1 = 0", args = emptyList())
                    val page = f.folderRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = hidden)

                    val tomb = page.items.firstOrNull { it.id == "secret-folder" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.rootPath shouldBe ""
                }
            }
        }

        test("admin_user_roster member pullSince delivers a roster tombstone with blanked PII") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.rosterRepo.upsert(
                        AdminUserRosterSyncPayload(
                            id = "doomed-user",
                            email = "secret@example.com",
                            displayName = "Secret Person",
                            role = "MEMBER",
                            status = "ACTIVE",
                            canShare = true,
                            accountCreatedAt = 123L,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    f.rosterRepo.softDelete("doomed-user")

                    // Mirrors ADMIN_USER_ROSTER_HIDDEN in SyncRoutes (private there): members see no roster rows.
                    val hidden = SqlFragment(sql = "SELECT id FROM admin_user_roster WHERE 1 = 0", args = emptyList())
                    val page = f.rosterRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = hidden)

                    val tomb = page.items.firstOrNull { it.id == "doomed-user" }.shouldNotBeNull()
                    tomb.deletedAt shouldNotBe null
                    tomb.email shouldBe ""
                    tomb.displayName shouldBe ""
                    tomb.role shouldBe ""
                    tomb.status shouldBe ""
                }
            }
        }

        test("collection_books tombstone keeps its junction identity (no minimization for this domain)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("some-book")
                val f = fixture()
                runTest {
                    f.collectionRepo.upsert(collection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "some-book"))
                    f.collectionBookRepo.softDelete(collectionId = "private-col", bookId = "some-book")

                    val extra = f.policy.accessibleCollectionBookIdsSql("member", UserRole.MEMBER)
                    val page = f.collectionBookRepo.pullSince("member", cursor = 0L, limit = 100, extraWhere = extra)

                    val tomb =
                        page.items
                            .firstOrNull { it.collectionId == "private-col" && it.bookId == "some-book" }
                            .shouldNotBeNull()
                    // The client applies the junction tombstone by (collectionId, bookId) — identity must survive.
                    tomb.deletedAt shouldNotBe null
                    tomb.collectionId shouldBe "private-col"
                    tomb.bookId shouldBe "some-book"
                }
            }
        }
    })

private data class TombstoneFixture(
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val activityRepo: ActivitySyncRepository,
    val folderRepo: LibraryFolderRepository,
    val rosterRepo: AdminUserRosterRepository,
    val policy: BookAccessPolicy,
)

private fun collection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
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

private fun grant(
    id: String,
    collectionId: String,
    memberId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = memberId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

/**
 * Makes [bookId] publicly visible the pure-union way (ALL_BOOKS membership + a member USER grant) —
 * mirrors [BookCatchUpAccessTest.makePublic].
 */
private suspend fun TombstoneFixture.makePublic(
    bookId: String,
    memberId: String,
) {
    collectionRepo.upsert(collection("all-books", owner = "system"))
    collectionBookRepo.upsert(membership("all-books", bookId))
    grantRepo.upsert(grant("grant-$bookId-$memberId", "all-books", memberId))
}
