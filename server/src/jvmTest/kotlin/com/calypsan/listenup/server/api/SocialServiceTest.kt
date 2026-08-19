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
import io.kotest.matchers.collections.shouldBeEmpty
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

        /** Inserts a playback position row directly (in-progress unless [finished]). */
        fun ListenUpDatabase.seedInProgressPosition(
            userId: String,
            bookId: String,
            positionMs: Long,
            lastPlayedAt: Long = 1L,
            finished: Boolean = false,
        ) {
            playbackPositionsQueries.insert(
                id = "$userId-$bookId",
                user_id = userId,
                book_id = bookId,
                position_ms = positionMs,
                last_played_at = lastPlayedAt,
                finished = if (finished) 1L else 0L,
                playback_speed = 1.0,
                volume_boost_db = 0.0,
                measured_gain_db = null,
                current_chapter_id = null,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = null,
                client_op_id = null,
            )
        }

        /** Inserts a live `active_sessions` presence row with an explicit [startedAt] (ordering fixture). */
        fun ListenUpDatabase.seedLiveSession(
            userId: String,
            bookId: String,
            startedAt: Long,
        ) {
            activeSessionsQueries.insert(
                session_id = "$userId-$bookId-session",
                user_id = userId,
                book_id = bookId,
                started_at = startedAt,
                created_at = startedAt,
                updated_at = startedAt,
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

        // ── 5: the recent-listen fill beneath the live sessions ──────────────────────

        test("currentlyListening fills non-live users with their most recently played unfinished book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("bob")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-old")
                sql.seedTestBook("book-new")
                sql.seedTestBook("book-done")
                sql.seedPublicProfile("bob", displayName = "Bob")
                runTest {
                    listOf("book-old", "book-new", "book-done").forEach {
                        makeBookAccessible(sql, driver, bookId = it, viewer = "viewer")
                    }
                    // Bob is not listening now. His newest UNFINISHED book is book-new; book-done is
                    // newer still but finished, so it must not be what the fill shows.
                    sql.seedInProgressPosition("bob", "book-old", positionMs = 10L, lastPlayedAt = 100L)
                    sql.seedInProgressPosition("bob", "book-new", positionMs = 10L, lastPlayedAt = 500L)
                    sql.seedInProgressPosition("bob", "book-done", positionMs = 10L, lastPlayedAt = 900L, finished = true)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    result shouldHaveSize 1
                    result.first().userId shouldBe "bob"
                    result.first().bookId shouldBe "book-new"
                    result.first().isLive shouldBe false
                    result.first().lastActiveAtMs shouldBe 500L
                }
            }
        }

        test("currentlyListening excludes the caller from the recent fill too") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewer = "viewer")
                    sql.seedInProgressPosition("viewer", "book-a", positionMs = 10L, lastPlayedAt = 500L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    // A user must never see themselves in this section — live or recent.
                    result.shouldBeEmpty()
                }
            }
        }

        test("currentlyListening never lists a live user a second time as a recent row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-live")
                sql.seedTestBook("book-recent")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-live", viewer = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-recent", viewer = "viewer")
                    sql.seedLiveSession("alice", "book-live", startedAt = 10L)
                    // A more recent position on a different book must NOT produce a second alice row.
                    sql.seedInProgressPosition("alice", "book-recent", positionMs = 10L, lastPlayedAt = 9_000L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    result shouldHaveSize 1
                    result.first().userId shouldBe "alice"
                    result.first().bookId shouldBe "book-live"
                    result.first().isLive shouldBe true
                }
            }
        }

        // ── 6 (CROWN JEWEL ACL): the recent fill is ACL-filtered exactly like the live rows ──

        test("currentlyListening omits a recent row whose book the caller cannot access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("bob")
                sql.seedTestUser("viewer")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("bob", displayName = "Bob")
                runTest {
                    makeBookInaccessible(sql, driver, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    makeBookAccessible(sql, driver, bookId = "public-book", viewer = "viewer")
                    // Alice's most recent unfinished book is one the viewer cannot access; Bob's is fine.
                    sql.seedInProgressPosition("alice", "private-book", positionMs = 10L, lastPlayedAt = 900L)
                    sql.seedInProgressPosition("bob", "public-book", positionMs = 10L, lastPlayedAt = 500L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    // Crown jewel: alice is dropped entirely rather than leaking the private book.
                    result shouldHaveSize 1
                    result.first().userId shouldBe "bob"
                    result.none { it.bookId == "private-book" } shouldBe true
                }
            }
        }

        test("ROOT sees recent rows on books no member collection reaches") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("root")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookInaccessible(sql, driver, bookId = "private-book", collectionId = "priv-col", collectionOwner = "alice")
                    sql.seedInProgressPosition("alice", "private-book", positionMs = 10L, lastPlayedAt = 900L)

                    // accessibleBookIds returns null for ROOT — unconstrained, so the fill is unfiltered.
                    val result =
                        makeService(sql, driver, principalFor("root", role = UserRole.ROOT))
                            .currentlyListening()
                            .value()

                    result shouldHaveSize 1
                    result.first().bookId shouldBe "private-book"
                    result.first().isLive shouldBe false
                }
            }
        }

        // ── 7: ordering — live first (startedAt desc), then recent (lastPlayedAt desc) ──

        test("currentlyListening orders live sessions first, then the recent fill") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                listOf("alice", "carol", "bob", "dave", "viewer").forEach { sql.seedTestUser(it) }
                listOf("b-alice", "b-carol", "b-bob", "b-dave").forEach { sql.seedTestBook(it) }
                listOf("alice", "carol", "bob", "dave").forEach { sql.seedPublicProfile(it, displayName = it) }
                runTest {
                    listOf("b-alice", "b-carol", "b-bob", "b-dave").forEach {
                        makeBookAccessible(sql, driver, bookId = it, viewer = "viewer")
                    }
                    // Two live listeners; carol started more recently than alice.
                    sql.seedLiveSession("alice", "b-alice", startedAt = 100L)
                    sql.seedLiveSession("carol", "b-carol", startedAt = 200L)
                    // Two non-live users whose last-played timestamps DWARF the live ones — proof
                    // the split is by liveness first, not by raw timestamp.
                    sql.seedInProgressPosition("bob", "b-bob", positionMs = 10L, lastPlayedAt = 90_000L)
                    sql.seedInProgressPosition("dave", "b-dave", positionMs = 10L, lastPlayedAt = 80_000L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    result.map { it.userId } shouldBe listOf("carol", "alice", "bob", "dave")
                    result.map { it.isLive } shouldBe listOf(true, true, false, false)
                }
            }
        }

        test("currentlyListening collapses a user's multiple live sessions to their newest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-early")
                sql.seedTestBook("book-late")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-early", viewer = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-late", viewer = "viewer")
                    sql.seedLiveSession("alice", "book-early", startedAt = 100L)
                    sql.seedLiveSession("alice", "book-late", startedAt = 200L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    // One row per user: the section shows each person once, on their newest book.
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-late"
                    result.first().lastActiveAtMs shouldBe 200L
                }
            }
        }

        test("currentlyListening drops a recent-fill user with no public identity") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("ghost")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewer = "viewer")
                    // No seedPublicProfile("ghost") — there is nobody to display.
                    sql.seedInProgressPosition("ghost", "book-a", positionMs = 10L, lastPlayedAt = 500L)

                    makeService(sql, driver, principalFor("viewer")).currentlyListening().value().shouldBeEmpty()
                }
            }
        }
    })
