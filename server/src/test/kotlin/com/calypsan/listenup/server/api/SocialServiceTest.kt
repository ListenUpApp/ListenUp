@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.PublicProfilesTable
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Contract and ACL tests for [SocialServiceImpl] — the crown-jewel ACL surface.
 *
 * Proves that:
 * 1. `currentlyListening()` excludes the caller's own session and joins identity from
 *    `public_profiles`.
 * 2. (CROWN JEWEL) A viewer never learns that someone is listening to a book they cannot
 *    access: only the accessible-book session is returned; the private-book one is omitted.
 * 3. `bookReaders(accessibleBook)` lists other readers; `bookReaders(inaccessibleBook)`
 *    returns `SocialError.NotFound` (never revealing the book exists).
 * 4. An unauthenticated caller receives `AppResult.Failure(SocialError.NotFound)`.
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
 */
class SocialServiceTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        fun makeService(
            db: Database,
            principal: PrincipalProvider,
        ): SocialServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return SocialServiceImpl(
                activeSessions = ActiveSessionRepository(db = db, bus = bus),
                bookAccessPolicy = BookAccessPolicy(db),
                publicProfiles = PublicProfileRepository(db = db, bus = bus, registry = registry),
                principal = principal,
            )
        }

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /** Inserts a `public_profiles` identity row directly (clients normally maintain it). */
        fun Database.seedPublicProfile(
            userId: String,
            displayName: String = "Display $userId",
            avatarType: String = "auto",
        ) {
            transaction(this) {
                PublicProfilesTable.insert {
                    it[id] = userId
                    it[PublicProfilesTable.displayName] = displayName
                    it[PublicProfilesTable.avatarType] = avatarType
                    it[revision] = 0L
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                    it[deletedAt] = null
                }
            }
        }

        /**
         * Gates [bookId] into a private collection owned by [collectionOwner] so it is
         * inaccessible to any non-admin user without an explicit share.
         */
        suspend fun makeBookInaccessible(
            db: Database,
            bookId: String,
            collectionId: String,
            collectionOwner: String = "stranger",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
            collectionRepo.upsert(
                CollectionSyncPayload(
                    id = collectionId,
                    libraryId = "test-library",
                    ownerId = collectionOwner,
                    name = collectionId,
                    isInbox = false,
                    isGlobalAccess = false,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(
                    collectionId = collectionId,
                    bookId = bookId,
                    createdAt = 0L,
                    revision = 0L,
                ),
            )
        }

        // ── 1: currentlyListening excludes the caller; identity from public_profiles ──

        test("currentlyListening excludes the caller's own session and joins identity from public_profiles") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("book-a")
                seedPublicProfile("alice", displayName = "Alice", avatarType = "image")
                seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    val sessions = ActiveSessionRepository(db = db, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "book-a")
                    sessions.startOrRefresh(userId = "viewer", bookId = "book-a")

                    val result =
                        makeService(db, principalFor("viewer"))
                            .currentlyListening()
                            .value()

                    result shouldHaveSize 1
                    result.first().userId shouldBe "alice"
                    result.first().displayName shouldBe "Alice"
                    result.first().avatarType shouldBe "image"
                    result.first().bookId shouldBe "book-a"
                }
            }
        }

        // ── 2 (CROWN JEWEL ACL): inaccessible-book session is never returned ──────────

        test("currentlyListening returns only the accessible-book session, never the private one") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("public-book")
                seedTestBook("private-book")
                seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    // "private-book" is gated into alice's private collection; viewer can't see it.
                    makeBookInaccessible(db, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")

                    val sessions = ActiveSessionRepository(db = db, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "public-book")
                    sessions.startOrRefresh(userId = "alice", bookId = "private-book")

                    val result =
                        makeService(db, principalFor("viewer"))
                            .currentlyListening()
                            .value()

                    // Crown jewel: the private-book session must be omitted, the public one present.
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "public-book"
                    result.none { it.bookId == "private-book" } shouldBe true
                }
            }
        }

        // ── 3: bookReaders on accessible / inaccessible books ────────────────────────

        test("bookReaders lists other readers of an accessible book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("book-a")
                seedPublicProfile("alice", displayName = "Alice")
                seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    val sessions = ActiveSessionRepository(db = db, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "book-a")
                    sessions.startOrRefresh(userId = "viewer", bookId = "book-a")

                    val result =
                        makeService(db, principalFor("viewer"))
                            .bookReaders(BookId("book-a"))
                            .value()

                    result shouldHaveSize 1
                    result.first().userId shouldBe "alice"
                    result.first().displayName shouldBe "Alice"
                }
            }
        }

        test("bookReaders returns Failure(SocialError.NotFound) for an inaccessible book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("private-book")
                runTest {
                    makeBookInaccessible(db, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    val sessions = ActiveSessionRepository(db = db, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "private-book")

                    val result =
                        makeService(db, principalFor("viewer"))
                            .bookReaders(BookId("private-book"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }

        // ── 4: Unauthenticated caller → NotFound ─────────────────────────────────────

        test("currentlyListening returns Failure(SocialError.NotFound) when caller is unauthenticated") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                runTest {
                    val result = makeService(db, noPrincipal()).currentlyListening()
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }

        test("bookReaders returns Failure(SocialError.NotFound) when caller is unauthenticated") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-a")
                runTest {
                    val result = makeService(db, noPrincipal()).bookReaders(BookId("book-a"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }
    })
