@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.ActivitySyncRepository
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Contract and ACL tests for [ActivityServiceImpl] — the activity-feed read surface.
 *
 * Proves that:
 * 1. `feed()` returns activities most-recent-first with identity joined from `public_profiles`.
 * 2. (CROWN JEWEL) A viewer never learns about activity on a book they cannot access: a
 *    `finished_book` on a private-collection book is omitted while one on a globally-accessible
 *    book is returned.
 * 3. Non-book activity (`shelf_created`, `user_joined`) always passes the ACL filter.
 * 4. The `before` cursor returns only older rows, DESC.
 * 5. Overfetch: a `limit=N` page still returns N visible rows when many inaccessible rows are
 *    interleaved and ≥N accessible rows exist.
 * 6. An unauthenticated caller receives `AppResult.Failure(SocialError.NotFound)`.
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks. A
 * [MutableClock] gives each recorded activity a distinct, advancing `created_at` so ordering and
 * cursor assertions are deterministic.
 */
class ActivityServiceTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        fun makeService(
            db: SqlTestDatabases,
            activities: ActivityRepository,
            principal: PrincipalProvider,
        ): ActivityServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ActivityServiceImpl(
                activities = activities,
                bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
                publicProfiles = PublicProfileRepository(db = db.sql, bus = bus, registry = registry),
                principal = principal,
            )
        }

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /**
         * A syncable [ActivityRecorder] over the test db driven by [clock] — the write-path that
         * seeds `activities` rows. Reads go through a plain [ActivityRepository] over the same db.
         */
        fun SqlTestDatabases.recorderWithClock(clock: Clock): ActivityRecorder =
            ActivityRecorder(
                syncRepo = ActivitySyncRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver),
                clock = clock,
            )

        /** Inserts a `public_profiles` identity row directly (clients normally maintain it). */
        fun ListenUpDatabase.seedPublicProfile(
            userId: String,
            displayName: String = "Display $userId",
            avatarType: String = "auto",
        ) {
            transaction {
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
        }

        /**
         * Gates [bookId] into a private collection owned by [collectionOwner] so it is
         * inaccessible to any non-admin user without an explicit share.
         */
        suspend fun makeBookInaccessible(
            db: SqlTestDatabases,
            bookId: String,
            collectionId: String,
            collectionOwner: String = "stranger",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
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
            db: SqlTestDatabases,
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
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val grantRepo =
                CollectionGrantRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
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

        // ── 1: feed returns most-recent-first, identity from public_profiles ──────────

        test("feed returns activities most-recent-first with identity from public_profiles") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestBook("book-a")
                sql.seedTestBook("book-b")
                sql.seedPublicProfile("alice", displayName = "Alice", avatarType = "image")
                runTest {
                    // Both books reachable to alice the pure-union way (ALL_BOOKS membership + alice's grant).
                    makeBookAccessible(this@withSqlDatabase, bookId = "book-a", viewer = "alice")
                    makeBookAccessible(this@withSqlDatabase, bookId = "book-b", viewer = "alice")

                    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    recorder.record(userId = "alice", type = ActivityType.STARTED_BOOK, bookId = "book-a")
                    clock.set(Instant.fromEpochMilliseconds(2_000L))
                    recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "book-b")

                    val result =
                        makeService(this@withSqlDatabase, activities, principalFor("alice"))
                            .feed(before = null, limit = 20)
                            .value()

                    result shouldHaveSize 2
                    // Most-recent-first: book-b (2_000) before book-a (1_000).
                    result[0].bookId shouldBe "book-b"
                    result[0].type shouldBe ActivityType.FINISHED_BOOK
                    result[0].occurredAtMs shouldBe 2_000L
                    result[0].displayName shouldBe "Alice"
                    result[0].avatarType shouldBe "image"
                    result[1].bookId shouldBe "book-a"
                    result[1].occurredAtMs shouldBe 1_000L
                }
            }
        }

        // ── 2 (CROWN JEWEL ACL): inaccessible-book activity is never returned ─────────

        test("feed returns the globally-accessible book activity, never the private one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("public-book")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    // "private-book" is gated into alice's private collection; viewer can't see it.
                    makeBookInaccessible(
                        this@withSqlDatabase,
                        bookId = "private-book",
                        collectionId = "priv-col",
                        collectionOwner = "alice",
                    )
                    // "public-book" is reachable to viewer the pure-union way (ALL_BOOKS membership + viewer's grant).
                    makeBookAccessible(this@withSqlDatabase, bookId = "public-book", viewer = "viewer")

                    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                    clock.set(Instant.fromEpochMilliseconds(2_000L))
                    recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "public-book")

                    val result =
                        makeService(this@withSqlDatabase, activities, principalFor("viewer"))
                            .feed(before = null, limit = 20)
                            .value()

                    // Crown jewel: the private-book activity must be omitted, the public one present.
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "public-book"
                    result.none { it.bookId == "private-book" } shouldBe true
                }
            }
        }

        // ── 3: non-book activity always passes the ACL filter ────────────────────────

        test("feed always returns non-book activity regardless of book access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookInaccessible(
                        this@withSqlDatabase,
                        bookId = "private-book",
                        collectionId = "priv-col",
                        collectionOwner = "alice",
                    )

                    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    // A book activity the viewer can't see, plus two non-book activities.
                    recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                    clock.set(Instant.fromEpochMilliseconds(2_000L))
                    recorder.record(
                        userId = "alice",
                        type = ActivityType.SHELF_CREATED,
                        shelfId = "shelf-1",
                        shelfName = "Favorites",
                    )
                    clock.set(Instant.fromEpochMilliseconds(3_000L))
                    recorder.record(userId = "alice", type = ActivityType.USER_JOINED)

                    val result =
                        makeService(this@withSqlDatabase, activities, principalFor("viewer"))
                            .feed(before = null, limit = 20)
                            .value()

                    // Only the two non-book activities survive; the private book activity is dropped.
                    result shouldHaveSize 2
                    result.map { it.type } shouldBe listOf(ActivityType.USER_JOINED, ActivityType.SHELF_CREATED)
                    result.none { it.bookId == "private-book" } shouldBe true
                    result.first { it.type == ActivityType.SHELF_CREATED }.shelfName shouldBe "Favorites"
                }
            }
        }

        // ── 4: before cursor returns only older rows, DESC ───────────────────────────

        test("feed before cursor returns only rows older than the cursor, DESC") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestBook("book-a")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    // "book-a" reachable to alice the pure-union way (ALL_BOOKS membership + alice's grant).
                    makeBookAccessible(this@withSqlDatabase, bookId = "book-a", viewer = "alice")

                    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    recorder.record(userId = "alice", type = ActivityType.USER_JOINED) // 1_000
                    clock.set(Instant.fromEpochMilliseconds(2_000L))
                    recorder.record(userId = "alice", type = ActivityType.STARTED_BOOK, bookId = "book-a") // 2_000
                    clock.set(Instant.fromEpochMilliseconds(3_000L))
                    recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "book-a") // 3_000

                    val result =
                        makeService(this@withSqlDatabase, activities, principalFor("alice"))
                            .feed(before = 3_000L, limit = 20)
                            .value()

                    // Only rows with created_at < 3_000, most-recent-first.
                    result shouldHaveSize 2
                    result.map { it.occurredAtMs } shouldBe listOf(2_000L, 1_000L)
                }
            }
        }

        // ── 5: overfetch fills a full page despite interleaved inaccessible rows ──────

        test("feed returns a full page of visible rows when inaccessible rows are interleaved") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("alice")
                sql.seedTestUser("viewer")
                sql.seedTestBook("private-book")
                sql.seedPublicProfile("alice", displayName = "Alice")
                runTest {
                    makeBookInaccessible(
                        this@withSqlDatabase,
                        bookId = "private-book",
                        collectionId = "priv-col",
                        collectionOwner = "alice",
                    )

                    val clock = MutableClock(Instant.fromEpochMilliseconds(0L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    // 20 accessible (non-book) rows interleaved with 20 inaccessible book rows.
                    var t = 1L
                    repeat(20) {
                        clock.set(Instant.fromEpochMilliseconds(t++))
                        recorder.record(userId = "alice", type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                        clock.set(Instant.fromEpochMilliseconds(t++))
                        recorder.record(userId = "alice", type = ActivityType.USER_JOINED)
                    }

                    val result =
                        makeService(this@withSqlDatabase, activities, principalFor("viewer"))
                            .feed(before = null, limit = 5)
                            .value()

                    // A full page of 5 visible rows, all non-book, despite the inaccessible interleave.
                    result shouldHaveSize 5
                    result.all { it.type == ActivityType.USER_JOINED } shouldBe true
                }
            }
        }

        // ── 6: unauthenticated caller → NotFound ─────────────────────────────────────

        // ── 7: feed orders by occurred_at, not insert order ──────────────────────────

        test("page orders by occurred_at, not insert order") {
            withSqlDatabase {
                runTest {
                    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(clock)
                    // Insert at clock=1_000 but with occurredAt=1_000 (older real event)
                    recorder.record(userId = "u1", type = ActivityType.LISTENING_SESSION, occurredAt = 1_000L)
                    // Insert at clock=2_000 but with occurredAt=9_000 (newer real event)
                    clock.set(Instant.fromEpochMilliseconds(2_000L))
                    recorder.record(userId = "u1", type = ActivityType.LISTENING_SESSION, occurredAt = 9_000L)

                    val page = activities.page(before = null, limit = 10)
                    page.map { it.occurredAt } shouldBe listOf(9_000L, 1_000L)
                }
            }
        }

        test("page breaks occurred_at ties deterministically by id DESC") {
            withSqlDatabase {
                runTest {
                    val activities = ActivityRepository(db = sql)
                    val recorder = recorderWithClock(MutableClock(Instant.fromEpochMilliseconds(1_000L)))
                    // Two activities sharing the same occurredAt — the secondary `id DESC` sort must order them.
                    recorder.record(userId = "u1", type = ActivityType.LISTENING_SESSION, occurredAt = 5_000L)
                    recorder.record(userId = "u1", type = ActivityType.LISTENING_SESSION, occurredAt = 5_000L)

                    val page = activities.page(before = null, limit = 10)
                    val ids = page.map { it.id }
                    ids shouldBe ids.sortedDescending()
                }
            }
        }

        test("feed returns Failure(SocialError.NotFound) when caller is unauthenticated") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val activities = ActivityRepository(db = sql)
                    val result = makeService(this@withSqlDatabase, activities, noPrincipal()).feed(before = null, limit = 20)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            }
        }
    })

/** A mutable [Clock] for tests that need to advance time deterministically. */
private class MutableClock(
    private var time: Instant,
) : Clock {
    override fun now(): Instant = time

    fun set(newTime: Instant) {
        time = newTime
    }
}
