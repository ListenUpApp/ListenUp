@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
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
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.makeBookAccessible
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
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

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
 * Uses a real in-memory migrated SQLite database + real repositories; no mocks.
 */
class SocialServiceTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        /**
         * The service over real repositories and the in-memory migrated database, with "now" pinned
         * to [nowMs] so a test can place a fixture inside or outside the staleness windows.
         *
         * [nowMs] defaults to the epoch, which puts every window's floor below any fixture timestamp
         * — a test that does not pin its own "now" is asserting something other than staleness, and
         * its seeded timestamps keep meaning exactly what they say.
         */
        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            principal: PrincipalProvider,
            nowMs: Long = 0L,
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
                listeningEvents = ListeningEventRepository(db = sql, bus = bus, registry = registry),
                bookReads = BookReadsRepository(db = sql),
                books = books,
                sql = sql,
                principal = principal,
                clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
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
            deletedAt: Long? = null,
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
                finished_at = null,
                has_custom_speed = 0L,
                has_custom_boost = 0L,
                current_chapter_id = null,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = deletedAt,
                client_op_id = null,
            )
        }

        /**
         * Appends a closed `listening_events` span directly — what a client posts when a listen
         * pauses or stops. Lasts [durationMs] (two minutes by default, comfortably a real listen)
         * and ends at [endedAt].
         */
        fun ListenUpDatabase.seedClosedListen(
            userId: String,
            bookId: String,
            endedAt: Long,
            durationMs: Long = 2 * 60_000L,
            deletedAt: Long? = null,
        ) {
            listeningEventsQueries.insert(
                id = "$userId-$bookId-$endedAt",
                user_id = userId,
                book_id = bookId,
                start_position_ms = 0L,
                end_position_ms = durationMs,
                started_at = endedAt - durationMs,
                ended_at = endedAt,
                playback_speed = 1.0,
                tz = "UTC",
                device_label = null,
                revision = 0L,
                created_at = endedAt,
                updated_at = endedAt,
                deleted_at = deletedAt,
                client_op_id = null,
            )
        }

        /** Inserts a live `active_sessions` presence row with an explicit [startedAt] (ordering fixture). */
        fun ListenUpDatabase.seedLiveSession(
            userId: String,
            bookId: String,
            startedAt: Long,
            updatedAt: Long = startedAt,
        ) {
            activeSessionsQueries.insert(
                session_id = "$userId-$bookId-session",
                user_id = userId,
                book_id = bookId,
                started_at = startedAt,
                created_at = startedAt,
                updated_at = updatedAt,
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
                avatar_frame = null,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = null,
                client_op_id = null,
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
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

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
                    makeBookAccessible(sql, driver, bookId = "public-book", viewerId = "viewer")

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
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

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
                    makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")

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

        test("bookReadership carries a reader's exact position and last play from the row their percent comes from") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("b1")
                sql.seedPublicProfile("u1", displayName = "User One")
                sql.seedPublicProfile("u2", displayName = "User Two")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")

                    setBookDuration("b1", totalDuration = 10_000L)
                    sql.seedFinish("u1-a", userId = "u1", bookId = "b1", finishedAt = 100L)
                    sql.seedInProgressPosition(userId = "u2", bookId = "b1", positionMs = 4_321L, lastPlayedAt = 7L)

                    val readers =
                        makeService(sql, driver, principalFor("u1"))
                            .bookReadership(BookId("b1"))
                            .value()
                            .readers

                    val reading = readers.first { it.userId == "u2" }
                    reading.currentProgressPct shouldBe 43
                    reading.currentPositionMs shouldBe 4_321L
                    reading.lastPlayedAtMs shouldBe 7L
                    val finished = readers.first { it.userId == "u1" }
                    finished.currentPositionMs shouldBe null
                    finished.lastPlayedAtMs shouldBe null
                }
            }
        }

        test("bookReadership carries pulled Hardcover reads apart from ListenUp finishes, and lists a Hardcover-only reader") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("b1")
                sql.seedPublicProfile("u1", displayName = "User One")
                sql.seedPublicProfile("u2", displayName = "User Two")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                    // A month apart: a different listen, not one logged in both places.
                    sql.seedFinish("u1-a", userId = "u1", bookId = "b1", finishedAt = A_MONTH_LATER)
                    sql.seedFinish("u1-h", userId = "u1", bookId = "b1", finishedAt = 100L, source = "hardcover")
                    sql.seedFinish("u2-h", userId = "u2", bookId = "b1", finishedAt = 200L, source = "hardcover")

                    val readers =
                        makeService(sql, driver, principalFor("u1"))
                            .bookReadership(BookId("b1"))
                            .value()
                            .readers

                    readers.first { it.userId == "u1" }.finishes shouldBe listOf(A_MONTH_LATER)
                    readers.first { it.userId == "u1" }.hardcoverFinishes shouldBe listOf(100L)
                    readers.first { it.userId == "u2" }.finishes shouldBe emptyList()
                    readers.first { it.userId == "u2" }.hardcoverFinishes shouldBe listOf(200L)
                }
            }
        }

        // ── 3b: a listen logged both in ListenUp and on Hardcover reads as one ───────────

        /**
         * u1's readership of b1, after seeding u1 (home [timezone]) with ListenUp finishes [own] and
         * Hardcover reads [hardcover], all epoch ms.
         */
        suspend fun SqlTestDatabases.readershipOf(
            own: List<Long>,
            hardcover: List<Long>,
            timezone: String = "UTC",
        ): BookReaderEntry {
            sql.seedTestLibraryAndFolder()
            sql.seedTestUser("u1", timezone = timezone)
            sql.seedTestBook("b1")
            sql.seedPublicProfile("u1", displayName = "Simon")
            makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
            own.forEachIndexed { i, at -> sql.seedFinish("own-$i", userId = "u1", bookId = "b1", finishedAt = at) }
            hardcover.forEachIndexed { i, at ->
                sql.seedFinish("hc-$i", userId = "u1", bookId = "b1", finishedAt = at, source = "hardcover")
            }
            return makeService(sql, driver, principalFor("u1"))
                .bookReadership(BookId("b1"))
                .value()
                .readers
                .single()
        }

        test("a Hardcover read the day before a ListenUp finish is the same listen: one row, also on Hardcover") {
            withSqlDatabase {
                runTest {
                    // 11/22/63: logged on Hardcover for Sep 30 (pulled at local noon), finished in ListenUp
                    // Oct 1 at 19:59 Edmonton — which is already Oct 2 in UTC.
                    val hardcover = at("2026-09-30T12:00", EDMONTON)
                    val listened = at("2026-10-01T19:59", EDMONTON)

                    val simon = readershipOf(own = listOf(listened), hardcover = listOf(hardcover), timezone = EDMONTON)

                    simon.finishes shouldBe listOf(listened)
                    simon.finishesAlsoOnHardcover shouldBe listOf(listened)
                    simon.hardcoverFinishes.shouldBeEmpty()
                }
            }
        }

        test("two ListenUp finishes and one Hardcover read are two rows, one of them also on Hardcover") {
            withSqlDatabase {
                runTest {
                    val first = at("2025-03-10T20:00", "UTC")
                    val reread = at("2026-10-01T20:00", "UTC")
                    val hardcover = at("2026-09-30T12:00", "UTC")

                    val simon = readershipOf(own = listOf(first, reread), hardcover = listOf(hardcover))

                    simon.finishes shouldBe listOf(reread, first)
                    simon.finishesAlsoOnHardcover shouldBe listOf(reread)
                    simon.hardcoverFinishes.shouldBeEmpty()
                }
            }
        }

        test("one ListenUp finish and two Hardcover reads are two rows: the nearer read pairs, the other stays") {
            withSqlDatabase {
                runTest {
                    val listened = at("2026-10-01T20:00", "UTC")
                    val near = at("2026-10-01T12:00", "UTC")
                    val alsoInWindow = at("2026-09-29T12:00", "UTC")

                    val simon = readershipOf(own = listOf(listened), hardcover = listOf(near, alsoInWindow))

                    simon.finishes shouldBe listOf(listened)
                    simon.finishesAlsoOnHardcover shouldBe listOf(listened)
                    simon.hardcoverFinishes shouldBe listOf(alsoInWindow)
                }
            }
        }

        test("a Hardcover read four days from a ListenUp finish is a different listen") {
            withSqlDatabase {
                runTest {
                    val listened = at("2026-10-05T20:00", "UTC")
                    val hardcover = at("2026-10-01T12:00", "UTC")

                    val simon = readershipOf(own = listOf(listened), hardcover = listOf(hardcover))

                    simon.finishes shouldBe listOf(listened)
                    simon.finishesAlsoOnHardcover.shouldBeEmpty()
                    simon.hardcoverFinishes shouldBe listOf(hardcover)
                }
            }
        }

        test("three calendar days apart in the reader's home timezone pairs, though UTC would call it four") {
            withSqlDatabase {
                runTest {
                    // Sep 28 → Oct 1 at home in Edmonton; the finish is already Oct 2 in UTC.
                    val hardcover = at("2026-09-28T12:00", EDMONTON)
                    val listened = at("2026-10-01T23:30", EDMONTON)

                    val simon = readershipOf(own = listOf(listened), hardcover = listOf(hardcover), timezone = EDMONTON)

                    simon.finishesAlsoOnHardcover shouldBe listOf(listened)
                    simon.hardcoverFinishes.shouldBeEmpty()
                }
            }
        }

        test("a reader with no home timezone is paired by UTC calendar days") {
            withSqlDatabase {
                runTest {
                    // Three days apart in Edmonton, but four in UTC — and UTC is the frame without a home.
                    val hardcover = at("2026-09-28T12:00", "UTC")
                    val listened = at("2026-10-02T01:00", "UTC")

                    val simon = readershipOf(own = listOf(listened), hardcover = listOf(hardcover))

                    simon.finishesAlsoOnHardcover.shouldBeEmpty()
                    simon.hardcoverFinishes shouldBe listOf(hardcover)
                }
            }
        }

        test("bookReadership does not show a reader who abandoned the book months ago as reading it") {
            // A book someone left at 40% in March is not something they are reading now. Showing
            // "reading, 40%" forever tells the viewer something false about a friend.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("bob")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("bob", displayName = "Bob")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    setBookDuration("book-a", totalDuration = 10_000L)

                    val nowMs = 1_800_000_000_000L
                    val day = 24L * 60 * 60 * 1000
                    sql.seedInProgressPosition(
                        userId = "alice",
                        bookId = "book-a",
                        positionMs = 4_000L,
                        lastPlayedAt = nowMs - 90 * day,
                    )
                    sql.seedInProgressPosition(
                        userId = "bob",
                        bookId = "book-a",
                        positionMs = 2_000L,
                        lastPlayedAt = nowMs - 3 * day,
                    )

                    val readers =
                        makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                            .bookReadership(BookId("book-a"))
                            .value()
                            .readers

                    readers.map { it.userId } shouldBe listOf("bob")
                    readers.single().currentProgressPct shouldBe 20
                }
            }
        }

        test("bookReadership ignores a deleted position") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    setBookDuration("book-a", totalDuration = 10_000L)
                    sql.seedInProgressPosition(
                        userId = "alice",
                        bookId = "book-a",
                        positionMs = 4_000L,
                        deletedAt = 2L,
                    )

                    makeService(sql, driver, principalFor("viewer"))
                        .bookReadership(BookId("book-a"))
                        .value()
                        .readers
                        .shouldBeEmpty()
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

        test("currentlyListening fills non-live users with the unfinished book of their latest closed listen") {
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
                        makeBookAccessible(sql, driver, bookId = it, viewerId = "viewer")
                    }
                    // Bob is not listening now. His newest listen on an UNFINISHED book is book-new;
                    // book-done is newer still but finished, so it must not be what the fill shows.
                    sql.seedClosedListen("bob", "book-old", endedAt = 100_000L)
                    sql.seedClosedListen("bob", "book-new", endedAt = 500_000L)
                    sql.seedClosedListen("bob", "book-done", endedAt = 900_000L)
                    sql.seedInProgressPosition("bob", "book-done", positionMs = 10L, lastPlayedAt = 900_000L, finished = true)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    result shouldHaveSize 1
                    result.first().userId shouldBe "bob"
                    result.first().bookId shouldBe "book-new"
                    result.first().isLive shouldBe false
                    result.first().lastActiveAtMs shouldBe 500_000L
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
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    sql.seedClosedListen("viewer", "book-a", endedAt = 500_000L)

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
                    makeBookAccessible(sql, driver, bookId = "book-live", viewerId = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-recent", viewerId = "viewer")
                    sql.seedLiveSession("alice", "book-live", startedAt = 10L)
                    // A more recent closed listen on a different book must NOT produce a second alice row.
                    sql.seedClosedListen("alice", "book-recent", endedAt = 9_000_000L)

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
                    makeBookAccessible(sql, driver, bookId = "public-book", viewerId = "viewer")
                    // Alice's latest listen is on a book the viewer cannot access; Bob's is fine.
                    sql.seedClosedListen("alice", "private-book", endedAt = 900_000L)
                    sql.seedClosedListen("bob", "public-book", endedAt = 500_000L)

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
                    sql.seedClosedListen("alice", "private-book", endedAt = 900_000L)

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
                        makeBookAccessible(sql, driver, bookId = it, viewerId = "viewer")
                    }
                    // Two live listeners; carol started more recently than alice.
                    sql.seedLiveSession("alice", "b-alice", startedAt = 100L)
                    sql.seedLiveSession("carol", "b-carol", startedAt = 200L)
                    // Two non-live users whose last listens DWARF the live ones — proof the split is
                    // by liveness first, not by raw timestamp.
                    sql.seedClosedListen("bob", "b-bob", endedAt = 90_000_000L)
                    sql.seedClosedListen("dave", "b-dave", endedAt = 80_000_000L)

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
                    makeBookAccessible(sql, driver, bookId = "book-early", viewerId = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-late", viewerId = "viewer")
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

        test("a listener who goes back to a book they started earlier is shown on it, not the one they sampled") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-returned-to")
                sql.seedTestBook("book-sampled")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-returned-to", viewerId = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-sampled", viewerId = "viewer")
                    // An hour on one book, a few seconds on another, then back to the first: its presence row
                    // was opened first but is the one still being refreshed.
                    sql.seedLiveSession("alice", "book-returned-to", startedAt = 100L, updatedAt = 400L)
                    sql.seedLiveSession("alice", "book-sampled", startedAt = 300L, updatedAt = 310L)

                    val result = makeService(sql, driver, principalFor("viewer")).currentlyListening().value()

                    result.single().bookId shouldBe "book-returned-to"
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
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    // No seedPublicProfile("ghost") — there is nobody to display.
                    sql.seedClosedListen("ghost", "book-a", endedAt = 500_000L)

                    makeService(sql, driver, principalFor("viewer")).currentlyListening().value().shouldBeEmpty()
                }
            }
        }

        // ── 8: liveness is a property of the row, not of when the sweep last ran ─────

        test("a presence row older than the live window is not reported as live") {
            // The cleanup sweep reclaims rows after 30 minutes, which is a garbage-collection
            // threshold. Liveness has to come from the row's own updated_at, or "Listening now" is
            // true only by luck of when the sweep last ran.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

                    val nowMs = 1_800_000_000_000L
                    // seedLiveSession stamps updated_at = startedAt. Six minutes old: past
                    // LIVE_WINDOW, still well inside the 30-minute sweep, so the row exists.
                    sql.seedLiveSession(userId = "alice", bookId = "book-a", startedAt = nowMs - 6 * 60_000L)

                    val result =
                        makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                            .currentlyListening()
                            .value()

                    result.none { it.isLive } shouldBe true
                }
            }
        }

        test("a presence row inside the live window is reported as live") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

                    val nowMs = 1_800_000_000_000L
                    sql.seedLiveSession(userId = "alice", bookId = "book-a", startedAt = nowMs - 60_000L)

                    val result =
                        makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                            .currentlyListening()
                            .value()

                    result.single().isLive shouldBe true
                }
            }
        }

        // ── 9: the recent fill only fills with something recent ─────────────────────

        test("the recent fill drops a book nobody has touched in months") {
            // The fill exists so a quiet server's section is not empty. A two-month-old row does not
            // achieve that — it reads as broken rather than quiet, which is the very failure the fill
            // was added to prevent. The section already renders nothing rather than invent a row.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

                    val nowMs = 1_800_000_000_000L
                    sql.seedClosedListen(userId = "alice", bookId = "book-a", endedAt = nowMs - 60L * 24 * 60 * 60 * 1000)

                    makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                        .currentlyListening()
                        .value()
                        .shouldBeEmpty()
                }
            }
        }

        test("the recent fill keeps a book played within the window") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

                    val nowMs = 1_800_000_000_000L
                    sql.seedClosedListen(userId = "alice", bookId = "book-a", endedAt = nowMs - 2L * 24 * 60 * 60 * 1000)

                    val result =
                        makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                            .currentlyListening()
                            .value()

                    result.single().isLive shouldBe false
                }
            }
        }

        // ── 10: the two windows hand off to one another ─────────────────────────────

        test("a stopped listener falls back to the recent fill rather than vanishing") {
            // The two halves are one roster. Someone who stopped twenty minutes ago should still
            // appear, just no longer as "listening now" — losing them entirely trades one wrong
            // answer for another.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                sql.seedPublicProfile("viewer", displayName = "Viewer")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")

                    val nowMs = 1_800_000_000_000L
                    val twentyMinutesAgo = nowMs - 20 * 60_000L
                    // Presence row past LIVE_WINDOW but not yet swept, plus the listen it closed.
                    sql.seedLiveSession(userId = "alice", bookId = "book-a", startedAt = twentyMinutesAgo)
                    sql.seedClosedListen(userId = "alice", bookId = "book-a", endedAt = twentyMinutesAgo)

                    val session =
                        makeService(sql, driver, principalFor("viewer"), nowMs = nowMs)
                            .currentlyListening()
                            .value()
                            .single()

                    session.isLive shouldBe false
                    session.bookId shouldBe "book-a"
                }
            }
        }
        // ── 11: the recent half is the Activity feed's evidence — a closed listen ──────

        test("a position moved with no closed listen does not put a user in the recent fill") {
            // Opening a book or scrubbing moves a position, but nobody listened: the Activity feed
            // says nothing, so Discover must not claim they were listening either.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    sql.seedInProgressPosition("alice", "book-a", positionMs = 120_000L, lastPlayedAt = 500_000L)

                    makeService(sql, driver, principalFor("viewer")).currentlyListening().value().shouldBeEmpty()
                }
            }
        }

        test("a closed listen puts a user in the recent fill, last active when it ended") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    // A later scrub moves the position without a listen; the listen's end is the truth.
                    sql.seedClosedListen("alice", "book-a", endedAt = 500_000L)
                    sql.seedInProgressPosition("alice", "book-a", positionMs = 120_000L, lastPlayedAt = 700_000L)

                    val row = makeService(sql, driver, principalFor("viewer")).currentlyListening().value().single()

                    row.userId shouldBe "alice"
                    row.bookId shouldBe "book-a"
                    row.isLive shouldBe false
                    row.lastActiveAtMs shouldBe 500_000L
                }
            }
        }

        test("a finished book stays out of the recent fill, however recent its last listen") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-done")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-done", viewerId = "viewer")
                    sql.seedClosedListen("alice", "book-done", endedAt = 900_000L)
                    sql.seedInProgressPosition("alice", "book-done", positionMs = 10L, lastPlayedAt = 900_000L, finished = true)

                    makeService(sql, driver, principalFor("viewer")).currentlyListening().value().shouldBeEmpty()
                }
            }
        }

        test("a deleted listen does not count") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    sql.seedClosedListen("alice", "book-a", endedAt = 500_000L, deletedAt = 600_000L)

                    makeService(sql, driver, principalFor("viewer")).currentlyListening().value().shouldBeEmpty()
                }
            }
        }

        test("a listen too short to be news does not count, as the Activity feed does not announce it") {
            // The feed never announces a sitting under RealListen.THRESHOLD_MS; Discover must not
            // claim someone was listening on the strength of that same eight-second tap.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-real")
                sql.seedTestBook("book-tap")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-real", viewerId = "viewer")
                    makeBookAccessible(sql, driver, bookId = "book-tap", viewerId = "viewer")
                    sql.seedClosedListen("alice", "book-real", endedAt = 500_000L)
                    sql.seedClosedListen("alice", "book-tap", endedAt = 900_000L, durationMs = 8_000L)

                    val row = makeService(sql, driver, principalFor("viewer")).currentlyListening().value().single()

                    row.bookId shouldBe "book-real"
                    row.lastActiveAtMs shouldBe 500_000L
                }
            }
        }

        test("short listens that add up to a real one in one sitting count, as the Activity feed shows them") {
            // ⛔ Discover judged each listen alone while the feed judged the sitting, so a stop-start evening the
            // feed announced was missing here. One rule now: 40s and 30s five minutes apart are one 70s sitting.
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookAccessible(sql, driver, bookId = "book-a", viewerId = "viewer")
                    sql.seedClosedListen("alice", "book-a", endedAt = 500_000L, durationMs = 30_000L)
                    sql.seedClosedListen("alice", "book-a", endedAt = 840_000L, durationMs = 40_000L)

                    val row = makeService(sql, driver, principalFor("viewer")).currentlyListening().value().single()

                    row.bookId shouldBe "book-a"
                    row.lastActiveAtMs shouldBe 840_000L
                }
            }
        }
    })

private const val EDMONTON = "America/Edmonton"

private const val A_MONTH_LATER = 30L * 24 * 60 * 60 * 1000

/** Epoch ms of the wall-clock [localDateTime] (ISO, minutes precision) in [zone]. */
private fun at(
    localDateTime: String,
    zone: String,
): Long = LocalDateTime.parse(localDateTime).toInstant(TimeZone.of(zone)).toEpochMilliseconds()

/**
 * Gates [bookId] into a private collection owned by [collectionOwner] so it is
 * inaccessible to any non-admin user without an explicit share.
 */
private suspend fun makeBookInaccessible(
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
