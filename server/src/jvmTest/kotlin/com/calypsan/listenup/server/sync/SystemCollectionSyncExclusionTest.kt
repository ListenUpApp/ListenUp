@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.SYSTEM_TYPE_ALL_BOOKS
import com.calypsan.listenup.server.api.SYSTEM_TYPE_INBOX
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

private const val PULL_LIMIT = 100

/**
 * Fragment-materialisation tests for the system-collection exclusion rules introduced in
 * Collections Phase 2b-i.
 *
 * System collections (`ALL_BOOKS`, `INBOX`) are server-managed substrate and must never
 * appear in a member's COLLECTION-domain sync payload. A book that lives in `ALL_BOOKS`
 * must still be visible to members via book-level visibility (the `accessibleBookIdsSql`
 * fragment is deliberately unchanged).
 *
 * Each test seeds an in-memory Flyway-migrated SQLite database, materialises the
 * relevant [BookAccessPolicy] SQL fragment against real data, and asserts the resulting
 * id set.
 */
class SystemCollectionSyncExclusionTest :
    FunSpec({

        fun makeRepos(db: Database): Triple<CollectionRepository, CollectionGrantRepository, CollectionBookRepository> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return Triple(
                CollectionRepository(db = db.asSqlDatabase(), bus = bus, registry = registry, driver = db.asSqlDriver()),
                CollectionGrantRepository(db = db.asSqlDatabase(), bus = bus, registry = registry, driver = db.asSqlDriver()),
                CollectionBookRepository(db = db.asSqlDatabase(), bus = bus, registry = registry, driver = db.asSqlDriver()),
            )
        }

        /**
         * Seeds the standard fixture used across most tests in this file:
         *  - library + folder
         *  - member user
         *  - system ALL_BOOKS collection (type stamped) with a default grant for the member
         *  - system INBOX collection (type stamped)
         *  - a normal collection owned by the member
         *  - a normal collection grant on a stranger's collection granting member access
         *  - a book in ALL_BOOKS
         *  - a book in the normal owned collection
         *
         * Returns (allBooksId, inboxId, normalOwnedId, strangerCollectionId,
         *          normalGrantId, defaultAllBooksGrantId, bookInAllBooks, bookInNormal)
         */
        suspend fun seedFixture(
            db: Database,
            collections: CollectionRepository,
            grants: CollectionGrantRepository,
            memberships: CollectionBookRepository,
        ): FixtureIds {
            db.seedTestLibraryAndFolder()
            db.seedTestUser("member")
            db.seedTestUser("stranger")
            db.seedTestBook("book-in-all-books")
            db.seedTestBook("book-in-normal")

            // System ALL_BOOKS collection — type is stamped server-side via setType
            collections.upsert(systemCollectionFixture("all-books-col", isInbox = false))
            collections.setType("all-books-col", SYSTEM_TYPE_ALL_BOOKS)

            // System INBOX collection — type stamped
            collections.upsert(systemCollectionFixture("inbox-col", isInbox = true))
            collections.setType("inbox-col", SYSTEM_TYPE_INBOX)

            // Member's default ALL_BOOKS grant (issued at registration)
            grants.upsert(grantFixture("default-all-books-grant", "all-books-col", sharedWith = "member"))

            // A normal collection owned by the member
            collections.upsert(normalCollectionFixture("normal-owned-col", owner = "member"))

            // A stranger's collection; member has a normal grant on it
            collections.upsert(normalCollectionFixture("stranger-col", owner = "stranger"))
            grants.upsert(grantFixture("normal-grant", "stranger-col", sharedWith = "member"))

            // Book memberships
            memberships.upsert(membershipFixture("all-books-col", "book-in-all-books"))
            memberships.upsert(membershipFixture("normal-owned-col", "book-in-normal"))

            return FixtureIds(
                allBooksId = "all-books-col",
                inboxId = "inbox-col",
                normalOwnedId = "normal-owned-col",
                strangerCollectionId = "stranger-col",
                normalGrantId = "normal-grant",
                defaultAllBooksGrantId = "default-all-books-grant",
                bookInAllBooks = "book-in-all-books",
                bookInNormal = "book-in-normal",
            )
        }

        // ---- accessibleCollectionIdsSql ----

        test("accessibleCollectionIdsSql excludes ALL_BOOKS and INBOX for member") {
            withInMemoryDatabase {
                val db = this
                val (collections, grants, memberships) = makeRepos(db)
                runTest {
                    val ids = seedFixture(db, collections, grants, memberships)

                    val policy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver())
                    val frag = policy.accessibleCollectionIdsSql("member", UserRole.MEMBER)

                    val page = collections.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)
                    val resultIds = page.items.map { it.id }

                    resultIds shouldNotContain ids.allBooksId
                    resultIds shouldNotContain ids.inboxId
                    resultIds shouldContain ids.normalOwnedId
                    resultIds shouldContain ids.strangerCollectionId
                }
            }
        }

        // ---- accessibleCollectionBookIdsSql ----

        test("accessibleCollectionBookIdsSql excludes ALL_BOOKS and INBOX membership rows for member") {
            withInMemoryDatabase {
                val db = this
                val (collections, grants, memberships) = makeRepos(db)
                runTest {
                    val ids = seedFixture(db, collections, grants, memberships)

                    val policy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver())
                    val frag = policy.accessibleCollectionBookIdsSql("member", UserRole.MEMBER)

                    val page = memberships.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)
                    val resultCollectionIds = page.items.map { it.collectionId }

                    resultCollectionIds shouldNotContain ids.allBooksId
                    resultCollectionIds shouldNotContain ids.inboxId
                    resultCollectionIds shouldContain ids.normalOwnedId
                }
            }
        }

        // ---- visibleCollectionGrantIdsSql ----

        test("visibleCollectionGrantIdsSql excludes the member's default ALL_BOOKS grant") {
            withInMemoryDatabase {
                val db = this
                val (collections, grants, memberships) = makeRepos(db)
                runTest {
                    val ids = seedFixture(db, collections, grants, memberships)

                    val policy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver())
                    val frag = policy.visibleCollectionGrantIdsSql("member", UserRole.MEMBER)

                    val page = grants.pullSince(userId = null, cursor = 0, limit = PULL_LIMIT, extraWhere = frag)
                    val resultGrantIds = page.items.map { it.id }

                    resultGrantIds shouldNotContain ids.defaultAllBooksGrantId
                    resultGrantIds shouldContain ids.normalGrantId
                }
            }
        }

        // ---- accessibleBookIdsSql — non-regression ----

        test("accessibleBookIdsSql STILL returns book in ALL_BOOKS (book-level visibility unchanged)") {
            withInMemoryDatabase {
                val db = this
                val (collections, grants, memberships) = makeRepos(db)
                runTest {
                    val ids = seedFixture(db, collections, grants, memberships)

                    val policy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver())

                    // The book in ALL_BOOKS is reachable via the grant branch even though
                    // ALL_BOOKS itself is excluded from the collection-domain sync fragments.
                    // accessibleBookIdsSql is deliberately unchanged.
                    val page = policy.accessibleBookIds("member", UserRole.MEMBER)

                    page!!.shouldContain(ids.bookInAllBooks)
                    page.shouldContain(ids.bookInNormal)
                }
            }
        }
    })

// ---- Fixture types ----

private data class FixtureIds(
    val allBooksId: String,
    val inboxId: String,
    val normalOwnedId: String,
    val strangerCollectionId: String,
    val normalGrantId: String,
    val defaultAllBooksGrantId: String,
    val bookInAllBooks: String,
    val bookInNormal: String,
)

// ---- Fixture builders ----

private fun systemCollectionFixture(
    id: String,
    isInbox: Boolean,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        // System collections are owned by the "system" sentinel; see SYSTEM_OWNER_ID
        ownerId = "system",
        name = if (isInbox) "Inbox" else "All Books",
        isInbox = isInbox,
        revision = 0L,
        updatedAt = 0L,
    )

private fun normalCollectionFixture(
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

private fun grantFixture(
    id: String,
    collectionId: String,
    sharedWith: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = sharedWith,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membershipFixture(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
