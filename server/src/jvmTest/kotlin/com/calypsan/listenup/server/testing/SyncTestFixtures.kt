package com.calypsan.listenup.server.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.routes.AVATAR_MAX_BYTES
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import java.nio.file.Files
import org.sqlite.SQLiteConfig

/**
 * The SQLDelight database + the driver behind it, over one freshly-migrated SQLite file —
 * the reusable fixture every SQLDelight-aggregate test uses.
 *
 * - [sql] is the [ListenUpDatabase] the repositories (TagRepository, BookTagRepository, …) take
 *   in their constructor.
 * - [driver] is the [app.cash.sqldelight.db.SqlDriver] that backs [sql] — exposed so tests can
 *   execute raw SQLDelight queries (e.g. access-filtered repo / [BookAccessPolicy] tests that wire
 *   the driver directly, or a test that plants a row bypassing a service-layer guard). It is the
 *   SAME instance backing [sql], so a raw query shares the connection of a transaction opened on
 *   [sql] — matching production, where one [SqlDriver] single backs the [ListenUpDatabase] single.
 */
data class SqlTestDatabases(
    val sql: ListenUpDatabase,
    val driver: app.cash.sqldelight.db.SqlDriver,
)

/**
 * Builds an [ActivitySyncRepository] over the test db — the syncable write-path for activities.
 * Uses a throwaway [SyncRegistry] per call (fine for unit tests that only need the repo to write
 * rows; a test exercising activity catch-up/digest against the real app must instead resolve the
 * DI-registered repo so the domain is in the app's registry).
 */
fun SqlTestDatabases.activitySyncRepository(bus: ChangeBus = ChangeBus()): ActivitySyncRepository =
    ActivitySyncRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)

/** Builds an [ActivityRecorder] over the test db's syncable activity repo. */
fun SqlTestDatabases.activityRecorder(bus: ChangeBus = ChangeBus()): ActivityRecorder =
    ActivityRecorder(syncRepo = activitySyncRepository(bus))

/**
 * Runs [block] with a freshly-migrated SQLite database as a SQLDelight [ListenUpDatabase] (plus its
 * backing driver) — the reusable fixture every SQLDelight-aggregate test uses.
 *
 * Migrations run once via [DatabaseFactory.init] (the same path production uses), so the full schema
 * is present before the SQLDelight driver opens the file; the driver never calls `Schema.create`. A
 * temp-file database (not `:memory:`) is used so WAL mode and the schema-history table behave exactly
 * as in production; the file is deleted on JVM exit.
 *
 * The SQLDelight driver is closed after [block] returns so the file handle is released
 * deterministically.
 */
fun withSqlDatabase(block: SqlTestDatabases.() -> Unit) {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    val path = tmp.absolutePath
    // DatabaseFactory.init runs the Flyway migrations against the file (its connection pool is left
    // open for the short test lifetime); the SQLDelight driver opened next reads that already-migrated
    // schema and never calls Schema.create.
    DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path"))
    // Test connections enforce foreign_keys + busy_timeout via JDBC connection PROPERTIES rather
    // than a one-time `driver.execute("PRAGMA …")` — JdbcSqliteDriver opens a connection per
    // operation, so a post-open PRAGMA configures only a transient connection and is silently lost
    // (which left ON DELETE CASCADE not firing). Production DriverFactory is intentionally NOT
    // changed here: enabling FK on every production connection currently breaks live-scan insert
    // ordering (LibraryLessOnboardingE2ETest) — that is the separate step-3 follow-up (FK-clean
    // scan + production FK enforcement, as part of optimizing the DB layer once we own it).
    val driver =
        JdbcSqliteDriver(
            "jdbc:sqlite:$path",
            SQLiteConfig()
                .apply {
                    enforceForeignKeys(true)
                    busyTimeout = 5000
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                }.toProperties(),
        )
    try {
        SqlTestDatabases(sql = ListenUpDatabase(driver), driver = driver).block()
    } finally {
        driver.close()
    }
}

/**
 * A [PrincipalProvider] that yields a ROOT [UserPrincipal] — used by impl-level tests that
 * drive metadata-mutation services (tag/genre/contributor/series/metadata) directly. The
 * mutations are `canEdit`-gated; ROOT passes implicitly, so this is the minimal principal a
 * mutation test needs. Member-deny tests build their own MEMBER principal instead.
 */
fun rootPrincipal(userId: String = "test-root"): PrincipalProvider =
    PrincipalProvider { UserPrincipal(UserId(userId), SessionId("test-session-$userId"), UserRole.ROOT) }

/** A [PrincipalProvider] yielding a MEMBER [UserPrincipal] for [userId] — for canEdit-deny tests. */
fun memberPrincipal(userId: String): PrincipalProvider =
    PrincipalProvider { UserPrincipal(UserId(userId), SessionId("test-session-$userId"), UserRole.MEMBER) }

/**
 * Canonical fixture builder for [BookSyncPayload] test instances.
 *
 * All fields have sensible defaults so a minimal call works for the common case.
 * Override specific fields by name as the test requires.
 *
 * Replaces two divergent inline fixtures that existed in `BookServiceImplTest`
 * (`bookFixture`) and `BookRepositoryUpsertTest` (`bookPayloadFixture`).
 *
 * @param id the book id — required (no universal default).
 * @param title the book title — required (no universal default).
 * @param rootRelPath the relative path; defaults to `"books/$id"`.
 * @param contributors pre-resolved [BookContributorPayload] list; default empty.
 * @param series pre-resolved [BookSeriesPayload] list; default empty.
 * @param chapters [BookChapterPayload] list; default empty.
 * @param audioFiles [BookAudioFilePayload] list; default empty. [BookSyncPayload.totalDuration]
 *   is derived as the sum of individual file durations.
 * @param cover optional [CoverPayload]; default null.
 */
fun bookPayloadFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    chapters: List<BookChapterPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
    cover: CoverPayload? = null,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = null,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = audioFiles.sumOf { it.duration },
        cover = cover,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = contributors,
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        // The substrate authors the persisted revision/timestamps; the wire payload
        // values here are placeholders for the test, ignored by writePayload.
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

// ── SQLDelight seed helpers ───────────────────────────────────────────────────

/**
 * Seeds a test library row with id `"test-library"` and a test folder row with id `"test-folder"`
 * into [this] database. Use this in tests that call
 * [com.calypsan.listenup.server.services.BookRepository.upsert] with `libraryId = LibraryId("test-library")`
 * and `folderId = FolderId("test-folder")`, so the `libraries` / `library_folders` FK constraints are
 * satisfied. DDL-defaulted columns are supplied explicitly: `metadata_precedence='embedded,abs,sidecar'`,
 * `access_mode='shared'`, `created_by_user_id=null`, `client_op_id=null`.
 *
 * Any existing folder row at [folderPath] is hard-deleted first (the bootstrap may have inserted one).
 *
 * @param folderPath the `library_folders.root_path` value (default `/tmp/test-library`).
 */
fun ListenUpDatabase.seedTestLibraryAndFolder(
    libraryId: String = "test-library",
    folderId: String = "test-folder",
    folderPath: String = "/tmp/test-library",
) {
    val now = System.currentTimeMillis()
    transaction {
        librariesQueries.insert(
            id = libraryId,
            name = "Test Library",
            metadata_precedence = "embedded,abs,sidecar",
            access_mode = "shared",
            created_by_user_id = null,
            created_at = now,
            revision = 0L,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
        // Bootstrap (LibraryRegistry) may have already inserted a folder at this
        // path. Remove it so we can insert the test-controlled row with the
        // canonical id that book fixtures reference via folderId.
        libraryFoldersQueries.deleteByRootPath(root_path = folderPath)
        libraryFoldersQueries.insert(
            id = folderId,
            library_id = libraryId,
            root_path = folderPath,
            created_at = now,
            revision = 0L,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

/**
 * Seeds a minimal `book_series` row with [id] into [this] database.
 *
 * Used in tests that insert `entities` rows — the FK `home_series_id REFERENCES book_series(id)`
 * requires the parent row to exist when FK enforcement is enabled. DDL-defaulted columns are
 * supplied explicitly: `sort_name=null`, `asin/description/cover_path/cover_blur_hash=null`.
 *
 * @param id the `book_series.id` value.
 * @param name the `book_series.name` / `normalized_name` value (default `"Test Series $id"`).
 */
fun ListenUpDatabase.seedTestSeries(
    id: String,
    name: String = "Test Series $id",
) {
    val now = System.currentTimeMillis()
    transaction {
        seriesQueries.insert(
            id = id,
            normalized_name = name.lowercase(),
            name = name,
            sort_name = null,
            revision = 0L,
            created_at = now,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            cover_path = null,
            cover_blur_hash = null,
        )
    }
}

/**
 * Seeds a minimal `books` row with [bookId] into [this] database.
 *
 * Used in tests that insert `book_tags` rows — the junction table's FK `book_id REFERENCES books(id)`
 * requires the parent row to exist when FK enforcement is enabled. DDL-defaulted columns are supplied
 * explicitly: `abridged=0`, `explicit=0`, `has_scan_warning=0`, nullable columns as null.
 *
 * @param libraryId the `books.library_id` value (default `"test-library"`).
 * @param folderId the `books.folder_id` value (default `"test-folder"`).
 * @param rootRelPath the `books.root_rel_path` value — the book directory relative to the
 *   folder root (default keeps the historic `"$bookId/book.m4b"` placeholder).
 */
fun ListenUpDatabase.seedTestBook(
    bookId: String,
    libraryId: String = "test-library",
    folderId: String = "test-folder",
    rootRelPath: String = "$bookId/book.m4b",
) {
    val now = System.currentTimeMillis()
    transaction {
        booksQueries.insert(
            id = bookId,
            library_id = libraryId,
            folder_id = folderId,
            title = "Test Book $bookId",
            sort_title = null,
            subtitle = null,
            description = null,
            publish_year = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = 0L,
            explicit = 0L,
            has_scan_warning = 0L,
            total_duration = 0L,
            cover_source = null,
            cover_path = null,
            cover_hash = null,
            user_edited_fields = "",
            root_rel_path = rootRelPath,
            inode = null,
            scanned_at = now,
            revision = 1L,
            created_at = now,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

/**
 * Seeds a minimal `users` row with [userId] into [this] database.
 *
 * Used in tests that insert `collection_shares` rows — the junction table's FK
 * `shared_with_user_id REFERENCES users(id)` requires the parent row to exist when FK enforcement is
 * enabled — and in permission-enforcement tests that need to control the per-user `canEdit`/`canShare`
 * flags or seed a soft-deleted user. DDL-defaulted columns are supplied explicitly: `avatar_type='auto'`,
 * `last_login_at=null`, `approved_by=null`, `approved_at=null`, `invited_by=null`, `tagline=null`.
 * Booleans are stored as `1L`/`0L` (SQLite INTEGER 0/1 affinity); enums by their `.name`.
 *
 * @param canEdit the `can_edit` flag (default true, matching the column default).
 * @param canShare the `can_share` flag (default true, matching the column default).
 * @param deletedAt the soft-delete tombstone in epoch-millis; null (default) is a live user.
 */
fun ListenUpDatabase.seedTestUser(
    userId: String,
    userRole: UserRoleColumn = UserRoleColumn.MEMBER,
    canEdit: Boolean = true,
    canShare: Boolean = true,
    deletedAt: Long? = null,
    timezone: String = "UTC",
) {
    transaction {
        usersQueries.insert(
            id = userId,
            email = "$userId@example.com",
            email_normalized = "$userId@example.com",
            password_hash = "phc",
            role = userRole.name,
            display_name = userId,
            status = UserStatusColumn.ACTIVE.name,
            created_at = 1L,
            updated_at = 1L,
            last_login_at = null,
            can_edit = if (canEdit) 1L else 0L,
            can_share = if (canShare) 1L else 0L,
            approved_by = null,
            approved_at = null,
            deleted_at = deletedAt,
            invited_by = null,
            tagline = null,
            avatar_type = "auto",
            timezone = timezone,
        )
    }
}

/**
 * Resolves the [UserRole] of a seeded user synchronously, for use as the `roleResolver` of
 * [testAuth]. Unseeded ids resolve to [UserRole.ROOT] — the historic test-auth default — so route
 * tests that send a bearer token without a matching user row keep their all-bypassing principal.
 */
fun ListenUpDatabase.roleOf(userId: String): UserRole =
    usersQueries
        .selectRoleById(userId)
        .executeAsOneOrNull()
        ?.let { UserRole.valueOf(it) }
        ?: UserRole.ROOT

/**
 * Returns a [PublicProfileMaintainer] backed by [this] database, for use in tests that exercise
 * [com.calypsan.listenup.server.services.UserStatsUpdater] but don't assert on the public-profiles
 * projection. The maintainer's [PublicProfileMaintainer.refresh] is a structural no-op when the user
 * row doesn't exist — so tests that don't call [seedTestUser] pay no cost beyond a single SELECT per
 * stats write.
 */
fun ListenUpDatabase.noOpPublicProfileMaintainer(): PublicProfileMaintainer =
    PublicProfileMaintainer(
        sql = this,
        publicProfileRepo = PublicProfileRepository(db = this, bus = ChangeBus(), registry = SyncRegistry()),
    )

/**
 * A throwaway [ImageStore] rooted at a fresh temp directory, for constructing [ProfileServiceImpl]
 * in tests that don't exercise the avatar disk-byte side-effect. The revert-to-auto delete tests
 * pass their own store so they can assert on it.
 */
fun tempAvatarImageStore(): ImageStore =
    ImageStore(
        baseDir = kotlinx.io.files.Path(Files.createTempDirectory("listenup-test-avatars-").toString()),
        maxBytes = AVATAR_MAX_BYTES,
    )
