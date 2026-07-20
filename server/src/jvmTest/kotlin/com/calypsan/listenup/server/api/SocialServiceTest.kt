@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import app.cash.sqldelight.db.SqlDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

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
            sql: ListenUpDatabase,
            driver: SqlDriver,
            principal: PrincipalProvider,
        ): SocialServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            // BookRepository registers global domains; give it its own registry to avoid
            // duplicate-domain registration against the per-test [registry] above.
            val bookRegistry = SyncRegistry()
            val books =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = bookRegistry,
                    contributorRepository =
                        ContributorRepository(
                            db = sql,
                            bus = bus,
                            registry = bookRegistry,
                        ),
                    seriesRepository = SeriesRepository(db = sql, bus = bus, registry = bookRegistry),
                    genreRepository = GenreRepository(db = sql, bus = bus, registry = bookRegistry),
                )
            return SocialServiceImpl(
                activeSessions = ActiveSessionRepository(db = sql, bus = ChangeBus()),
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                publicProfiles = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                playbackPositions = PlaybackPositionRepository(db = sql, bus = bus, registry = registry),
                bookReads = BookReadsRepository(db = sql),
                books = books,
                principal = principal,
            )
        }

        /** Sets a book's `total_duration` (ms); [seedTestBook] inserts 0L by default.
         *  A single-column SQLDelight update — no full upsert needed. */
        fun SqlTestDatabases.setBookDuration(
            bookId: String,
            totalDuration: Long,
        ) {
            sql.booksQueries.updateTotalDuration(total_duration = totalDuration, id = bookId)
        }

        /** Inserts an in-progress (unfinished) playback position row directly. */
        fun ListenUpDatabase.seedInProgressPosition(
            userId: String,
            bookId: String,
            positionMs: Long,
        ) {
            playbackPositionsQueries.insert(
                id = "$userId-$bookId",
                user_id = userId,
                book_id = bookId,
                position_ms = positionMs,
                last_played_at = 1L,
                finished = 0L,
                playback_speed = 1.0,
                current_chapter_id = null,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = null,
                client_op_id = null,
            )
        }

        /** Appends a `book_reads` completion row directly (newest-first ordering is by [finishedAt]). */
        fun ListenUpDatabase.seedFinish(
            id: String,
            userId: String,
            bookId: String,
            finishedAt: Long,
            source: String = "playback",
        ) {
            bookReadsQueries.insert(
                id = id,
                user_id = userId,
                book_id = bookId,
                finished_at = finishedAt,
                source = source,
                created_at = finishedAt,
            )
        }

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /** Inserts a `public_profiles` identity row directly (clients normally maintain it). */
        fun ListenUpDatabase.seedPublicProfile(
            userId: String,
            displayName: String = "Display $userId",
            avatarType: String = "auto",
        ) {
            publicProfilesQueries.insert(
                id = userId,
                display_name = displayName,
                avatar_type = avatarType,
                tagline = null,
                total_seconds_all_time = 0L,
                total_seconds_last_7_days = 0L,
                total_seconds_last_30_days = 0L,
                total_seconds_last_365_days = 0L,
                books_finished = 0L,
                current_streak_days = 0L,
                longest_streak_days = 0L,
                books_finished_last_7_days = 0L,
                books_finished_last_30_days = 0L,
                books_finished_last_365_days = 0L,
                longest_streak_last_7_days = 0L,
                longest_streak_last_30_days = 0L,
                longest_streak_last_365_days = 0L,
                avatar_updated_at = 0L,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = null,
                client_op_id = null,
            )
        }

        /**
         * Gates [bookId] into a private collection owned by [collectionOwner] so it is
         * inaccessible to any non-admin user without an explicit share.
         */
        suspend fun makeBookInaccessible(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookId: String,
            collectionId: String,
            collectionOwner: String = "stranger",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            collectionRepo.upsert(
                CollectionSyncPayload(
                    id = collectionId,
                    libraryId = "test-library",
                    ownerId = collectionOwner,
                    name = collectionId,
                    isInbox = false,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(
                    id = "$collectionId:$bookId",
                    collectionId = collectionId,
                    bookId = bookId,
                    createdAt = 0L,
                    revision = 0L,
                ),
            )
        }

        /**
         * Makes [bookId] visible to [viewer] the pure-union way: adds it to the per-library
         * ALL_BOOKS system collection (owned by "system") and grants [viewer] a live Read share
         * on that collection. [viewer] MUST already be seeded via [seedTestUser] — the grant's
         * `principal_id` is a FK into `users(id)`. The ALL_BOOKS collection is created once and
         * reused across calls (idempotent upsert), so multiple books / viewers stack cleanly.
         */
        suspend fun makeBookAccessible(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookId: String,
            viewer: String,
            // Grant id is keyed on (collection, viewer), NOT the book: the per-(collection,principal)
            // grant is unique, so repeated calls for the same viewer must reuse this row (upsert).
            grantId: String = "grant-$viewer",
            allBooksId: String = "all-books",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val grantRepo =
                CollectionGrantRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            collectionRepo.upsert(
                CollectionSyncPayload(
                    id = allBooksId,
                    libraryId = "test-library",
                    ownerId = "system",
                    name = "All Books",
                    isInbox = false,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(
                    id = "$allBooksId:$bookId",
                    collectionId = allBooksId,
                    bookId = bookId,
                    createdAt = 0L,
                    revision = 0L,
                ),
            )
            grantRepo.upsert(
                CollectionShareSyncPayload(
                    id = grantId,
                    collectionId = allBooksId,
                    sharedWithUserId = viewer,
                    sharedByUserId = "system",
                    permission = SharePermission.Read,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
        }

        // ── 1: currentlyListening excludes the caller; identity from public_profiles ──

        test("currentlyListening excludes the caller's own session and joins identity from public_profiles") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice", avatarType = "image")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    // "book-a" reachable to the caller (viewer) the pure-union way (ALL_BOOKS membership + viewer's grant).
                    makeBookAccessible(sql, driver, bookId = "book-a", viewer = "viewer")

                    val sessions = ActiveSessionRepository(db = sql, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "book-a")
                    sessions.startOrRefresh(userId = "viewer", bookId = "book-a")

                    val result =
                        makeService(sql, driver, principalFor("viewer"))
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    // "private-book" is gated into alice's private collection; viewer can't see it.
                    makeBookInaccessible(sql, driver, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    // "public-book" is reachable to the caller (viewer) the pure-union way (ALL_BOOKS membership + viewer's grant).
                    makeBookAccessible(sql, driver, bookId = "public-book", viewer = "viewer")

                    val sessions = ActiveSessionRepository(db = sql, bus = ChangeBus())
                    sessions.startOrRefresh(userId = "alice", bookId = "public-book")
                    sessions.startOrRefresh(userId = "alice", bookId = "private-book")

                    val result =
                        makeService(sql, driver, principalFor("viewer"))
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    // "book-a" reachable to the caller (viewer) the pure-union way (ALL_BOOKS membership + viewer's grant).
                    makeBookAccessible(sql, driver, bookId = "book-a", viewer = "viewer")

                    setBookDuration("book-a", totalDuration = 10_000L)
                    sql.seedFinish("alice-1", userId = "alice", bookId = "book-a", finishedAt = 500L)
                    sql.seedInProgressPosition(userId = "viewer", bookId = "book-a", positionMs = 2_000L)

                    val readers =
                        makeService(sql, driver, principalFor("viewer"))
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("private-book")
                runTest {
                    makeBookInaccessible(sql, driver, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    sql.seedFinish("alice-1", userId = "alice", bookId = "private-book", finishedAt = 500L)

                    val result =
                        makeService(sql, driver, principalFor("viewer"))
                            .bookReadership(BookId("private-book"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }

        test("bookReadership returns current progress + finish history, including the caller") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("b1")
                sql.seedPublicProfile("u1", displayName = "User One")
                sql.seedPublicProfile("u2", displayName = "User Two")
                runTest {
                    // "b1" reachable to the caller (u1) the pure-union way (ALL_BOOKS membership + u1's grant).
                    makeBookAccessible(sql, driver, bookId = "b1", viewer = "u1")

                    setBookDuration("b1", totalDuration = 10_000L)
                    // Caller u1 finished b1 twice (100L, 300L); u2 is in progress at 4_300/10_000.
                    sql.seedFinish("u1-a", userId = "u1", bookId = "b1", finishedAt = 100L)
                    sql.seedFinish("u1-b", userId = "u1", bookId = "b1", finishedAt = 300L)
                    sql.seedInProgressPosition(userId = "u2", bookId = "b1", positionMs = 4_300L)

                    val readers =
                        makeService(sql, driver, principalFor("u1"))
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val result = makeService(sql, driver, noPrincipal()).currentlyListening()
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }

        test("bookReadership returns Failure(SocialError.NotFound) when caller is unauthenticated") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-a")
                runTest {
                    val result = makeService(sql, driver, noPrincipal()).bookReadership(BookId("book-a"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }
    })
