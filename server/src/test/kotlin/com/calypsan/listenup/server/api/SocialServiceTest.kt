@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.BookReadsTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.PlaybackPositionTable
import com.calypsan.listenup.server.db.PublicProfilesTable
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Contract and ACL tests for [SocialServiceImpl] — the crown-jewel ACL surface.
 *
 * Proves that:
 * 1. `currentlyListening()` excludes the caller's own session and joins identity from
 *    `public_profiles`.
 * 2. (CROWN JEWEL) A viewer never learns that someone is listening to a book they cannot
 *    access: only the accessible-book session is returned; the private-book one is omitted.
 * 3. `bookReadership(accessibleBook)` lists every reader (including the caller) with their
 *    current progress% and dated finish history; `bookReadership(inaccessibleBook)` returns
 *    `SocialError.NotFound` (never revealing the book exists).
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
            // BookRepository registers global domains; give it its own registry to avoid
            // duplicate-domain registration against the per-test [registry] above.
            val bookRegistry = SyncRegistry()
            val books =
                BookRepository(
                    db = db,
                    bus = bus,
                    registry = bookRegistry,
                    contributorRepository = ContributorRepository(db = db, bus = bus, registry = bookRegistry),
                    seriesRepository = SeriesRepository(db = db, bus = bus, registry = bookRegistry),
                )
            return SocialServiceImpl(
                activeSessions = ActiveSessionRepository(db = db, bus = bus),
                bookAccessPolicy = BookAccessPolicy(db),
                publicProfiles = PublicProfileRepository(db = db, bus = bus, registry = registry),
                playbackPositions = PlaybackPositionRepository(db = db, bus = bus, registry = registry),
                bookReads = BookReadsRepository(db = db),
                books = books,
                principal = principal,
            )
        }

        /** Sets a book's `total_duration` (ms); [seedTestBook] inserts 0L by default. */
        fun Database.setBookDuration(
            bookId: String,
            totalDuration: Long,
        ) {
            transaction(this) {
                BookTable.update({ BookTable.id eq bookId }) { it[BookTable.totalDuration] = totalDuration }
            }
        }

        /** Inserts an in-progress (unfinished) playback position row directly. */
        fun Database.seedInProgressPosition(
            userId: String,
            bookId: String,
            positionMs: Long,
        ) {
            transaction(this) {
                PlaybackPositionTable.insert {
                    it[id] = "$userId-$bookId"
                    it[PlaybackPositionTable.userId] = userId
                    it[PlaybackPositionTable.bookId] = bookId
                    it[PlaybackPositionTable.positionMs] = positionMs
                    it[lastPlayedAt] = 1L
                    it[finished] = false
                    it[revision] = 0L
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                    it[deletedAt] = null
                }
            }
        }

        /** Appends a `book_reads` completion row directly (newest-first ordering is by [finishedAt]). */
        fun Database.seedFinish(
            id: String,
            userId: String,
            bookId: String,
            finishedAt: Long,
            source: String = "playback",
        ) {
            transaction(this) {
                BookReadsTable.insert {
                    it[BookReadsTable.id] = id
                    it[BookReadsTable.userId] = userId
                    it[BookReadsTable.bookId] = bookId
                    it[BookReadsTable.finishedAt] = finishedAt
                    it[BookReadsTable.readSource] = source
                    it[BookReadsTable.createdAt] = finishedAt
                }
            }
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

        // ── 3: bookReadership on accessible / inaccessible books ─────────────────────

        test("bookReadership lists every reader of an accessible book, including the caller") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("book-a")
                seedPublicProfile("alice", displayName = "Alice")
                seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    db.setBookDuration("book-a", totalDuration = 10_000L)
                    db.seedFinish("alice-1", userId = "alice", bookId = "book-a", finishedAt = 500L)
                    db.seedInProgressPosition(userId = "viewer", bookId = "book-a", positionMs = 2_000L)

                    val readers =
                        makeService(db, principalFor("viewer"))
                            .bookReadership(BookId("book-a"))
                            .value()
                            .readers

                    // The caller (viewer) is now included alongside alice.
                    readers shouldHaveSize 2
                    readers.map { it.userId }.toSet() shouldBe setOf("alice", "viewer")
                    readers.first { it.userId == "viewer" }.currentProgressPct shouldBe 20
                    readers.first { it.userId == "alice" }.finishes shouldBe listOf(500L)
                }
            }
        }

        test("bookReadership returns Failure(SocialError.NotFound) for an inaccessible book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("alice")
                seedTestUser("viewer")
                seedTestBook("private-book")
                runTest {
                    makeBookInaccessible(db, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    db.seedFinish("alice-1", userId = "alice", bookId = "private-book", finishedAt = 500L)

                    val result =
                        makeService(db, principalFor("viewer"))
                            .bookReadership(BookId("private-book"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }

        test("bookReadership returns current progress + finish history, including the caller") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("b1")
                seedPublicProfile("u1", displayName = "User One")
                seedPublicProfile("u2", displayName = "User Two")
                runTest {
                    db.setBookDuration("b1", totalDuration = 10_000L)
                    // Caller u1 finished b1 twice (100L, 300L); u2 is in progress at 4_300/10_000.
                    db.seedFinish("u1-a", userId = "u1", bookId = "b1", finishedAt = 100L)
                    db.seedFinish("u1-b", userId = "u1", bookId = "b1", finishedAt = 300L)
                    db.seedInProgressPosition(userId = "u2", bookId = "b1", positionMs = 4_300L)

                    val readers =
                        makeService(db, principalFor("u1"))
                            .bookReadership(BookId("b1"))
                            .value()
                            .readers

                    readers.first { it.userId == "u2" }.currentProgressPct shouldBe 43
                    readers.first { it.userId == "u1" }.finishes shouldBe listOf(300L, 100L) // newest-first
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

        test("bookReadership returns Failure(SocialError.NotFound) when caller is unauthenticated") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-a")
                runTest {
                    val result = makeService(db, noPrincipal()).bookReadership(BookId("book-a"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }
    })
