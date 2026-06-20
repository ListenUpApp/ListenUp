package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

/**
 * Runs [block] with a freshly-migrated in-memory SQLite [Database] as the receiver.
 *
 * Uses a temp-file database (not `:memory:`) so that Flyway's schema-history
 * table and SQLite's single-connection constraint both work correctly. The file
 * is deleted on JVM exit.
 */
fun withInMemoryDatabase(block: Database.() -> Unit) {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    val db = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).database
    db.block()
}

/**
 * Both DB handles over one migrated SQLite file, passed to every SQLDelight-aggregate test.
 *
 * - [sql] is the [ListenUpDatabase] the converted repositories (TagRepository,
 *   BookTagRepository, …) take in their constructor.
 * - [exposed] is the Exposed [Database] over the **same file**, for the seed helpers
 *   ([seedTestLibraryAndFolder] / [seedTestBook] / [seedTestUser]) and for any
 *   not-yet-converted collaborator (e.g. `BookSearchReindexer`, the service-layer `db`)
 *   that still speaks Exposed during the cutover.
 *
 * Both views read and write the one underlying file, so a row seeded through [exposed]
 * is visible to [sql] and vice versa.
 */
data class SqlTestDatabases(
    val sql: ListenUpDatabase,
    val exposed: Database,
)

/**
 * Runs [block] with a freshly-migrated SQLite database exposed as **both** a SQLDelight
 * [ListenUpDatabase] and an Exposed [Database] over the same temp file — the reusable
 * fixture every SQLDelight-aggregate test uses.
 *
 * Migrations run once via [DatabaseFactory.init] (the same path production uses), so the
 * full schema is present before the SQLDelight driver opens the file; the driver never
 * calls `Schema.create`. A temp-file database (not `:memory:`) is used so WAL mode and
 * the schema-history table behave exactly as in production; the file is deleted on JVM exit.
 *
 * The SQLDelight driver is closed after [block] returns so the file handle is released
 * deterministically.
 */
fun withSqlDatabase(block: SqlTestDatabases.() -> Unit) {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    val path = tmp.absolutePath
    val exposed = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path")).database
    val driver = DriverFactory().createDriver(path)
    try {
        SqlTestDatabases(sql = ListenUpDatabase(driver), exposed = exposed).block()
    } finally {
        driver.close()
    }
}

/**
 * The SQLDelight view + the driver behind it, cached together per Exposed [Database] file so
 * [asSqlDatabase] and [asSqlDriver] hand back the SAME instances on repeated calls within a test
 * — and crucially, the [ListenUpDatabase] is built over the cached [SqlDriver], so a raw query
 * run through [asSqlDriver] inside a `suspendTransaction(asSqlDatabase())` shares the connection
 * (matching production wiring, where one [SqlDriver] single backs the [ListenUpDatabase] single).
 */
private data class SqlViewBundle(
    val driver: app.cash.sqldelight.db.SqlDriver,
    val db: ListenUpDatabase,
)

private val sqlViewsByUrl = java.util.concurrent.ConcurrentHashMap<String, SqlViewBundle>()

private fun Database.sqlViewBundle(): SqlViewBundle {
    val path = url.removePrefix("jdbc:sqlite:")
    return sqlViewsByUrl.getOrPut(path) {
        val driver = DriverFactory().createDriver(path)
        SqlViewBundle(driver = driver, db = ListenUpDatabase(driver))
    }
}

/**
 * Returns a SQLDelight [ListenUpDatabase] over the **same file** this Exposed [Database] is
 * connected to — the bridge that lets existing `withInMemoryDatabase { … }` tests construct
 * SQLDelight-backed repositories (TagRepository, BookTagRepository) without rewriting their
 * whole fixture during the incremental cutover.
 *
 * The schema is already migrated (the Exposed [Database] was built by [DatabaseFactory.init],
 * which runs [com.calypsan.listenup.server.db.MigrationRunner]), so the driver never calls
 * `Schema.create`. Both views read/write the one file; in WAL mode the second connection is
 * harmless. Cached per file URL so repeated calls in one test reuse a single driver.
 *
 * Prefer [withSqlDatabase] for new SQLDelight-aggregate tests; this exists only for the
 * Exposed-fixture call sites that wire a converted repo as a collaborator.
 */
fun Database.asSqlDatabase(): ListenUpDatabase = sqlViewBundle().db

/**
 * Returns the SQLDelight [app.cash.sqldelight.db.SqlDriver] backing [asSqlDatabase] for the same
 * file — the same instance, so a raw `executeQuery` through it participates in a transaction
 * opened on [asSqlDatabase]. Wired to the access-filtered repos / [BookAccessPolicy] /
 * [com.calypsan.listenup.server.api.SearchServiceImpl] the way the production [SqlDriver] single is.
 */
fun Database.asSqlDriver(): app.cash.sqldelight.db.SqlDriver = sqlViewBundle().driver

/**
 * Seeds a test library row with id `"test-library"` and a test folder row with
 * id `"test-folder"` into the receiver database. Use this in tests that
 * call [com.calypsan.listenup.server.services.BookRepository.upsert] with
 * `libraryId = LibraryId("test-library")` and `folderId = FolderId("test-folder")`,
 * so the FK constraints in [LibraryTable] and [LibraryFolderTable] are satisfied.
 *
 * @param folderPath the [LibraryFolderTable.rootPath] value (default `/tmp/test-library`).
 */
fun Database.seedTestLibraryAndFolder(
    libraryId: String = "test-library",
    folderId: String = "test-folder",
    folderPath: String = "/tmp/test-library",
) {
    val now = System.currentTimeMillis()
    transaction(this) {
        LibraryTable.insert {
            it[LibraryTable.id] = libraryId
            it[LibraryTable.name] = "Test Library"
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        // Bootstrap (LibraryRegistry) may have already inserted a folder at this
        // path. Remove it so we can insert the test-controlled row with the
        // canonical id ("test-folder") that book fixtures reference via folderId.
        LibraryFolderTable.deleteWhere { LibraryFolderTable.rootPath eq folderPath }
        LibraryFolderTable.insert {
            it[LibraryFolderTable.id] = folderId
            it[LibraryFolderTable.libraryId] = libraryId
            it[LibraryFolderTable.rootPath] = folderPath
            it[LibraryFolderTable.createdAt] = now
            it[LibraryFolderTable.updatedAt] = now
            it[LibraryFolderTable.revision] = 0L
            it[LibraryFolderTable.deletedAt] = null
        }
    }
}

/**
 * Seeds a minimal book row with the given [bookId] into the receiver database.
 *
 * Used in tests that insert `book_tags` rows — the junction table's FK
 * `book_id REFERENCES books(id)` requires the parent row to exist when FK
 * enforcement is enabled.
 *
 * @param libraryId the [BookTable.libraryId] value (default `"test-library"`).
 * @param folderId the [BookTable.folderId] value (default `"test-folder"`).
 */
fun Database.seedTestBook(
    bookId: String,
    libraryId: String = "test-library",
    folderId: String = "test-folder",
) {
    val now = System.currentTimeMillis()
    transaction(this) {
        BookTable.insert {
            it[BookTable.id] = bookId
            it[BookTable.libraryId] = libraryId
            it[BookTable.folderId] = folderId
            it[BookTable.title] = "Test Book $bookId"
            it[BookTable.totalDuration] = 0L
            it[BookTable.rootRelPath] = "$bookId/book.m4b"
            it[BookTable.scannedAt] = now
            it[BookTable.revision] = 1L
            it[BookTable.createdAt] = now
            it[BookTable.updatedAt] = now
        }
    }
}

/**
 * Seeds a minimal user row with the given [userId] into the receiver database.
 *
 * Used in tests that insert `collection_shares` rows — the junction table's FK
 * `shared_with_user_id REFERENCES users(id)` requires the parent row to exist when FK
 * enforcement is enabled — and in permission-enforcement tests that need to control the
 * per-user `canEdit`/`canShare` flags or seed a soft-deleted user.
 *
 * @param canEdit the `can_edit` flag (default true, matching the column default).
 * @param canShare the `can_share` flag (default true, matching the column default).
 * @param deletedAt the soft-delete tombstone in epoch-millis; null (default) is a live user.
 */
fun Database.seedTestUser(
    userId: String,
    userRole: UserRoleColumn = UserRoleColumn.MEMBER,
    canEdit: Boolean = true,
    canShare: Boolean = true,
    deletedAt: Long? = null,
    timezone: String = "UTC",
) {
    transaction(this) {
        UserEntity.new(userId) {
            email = "$userId@example.com"
            emailNormalized = "$userId@example.com"
            passwordHash = "phc"
            role = userRole
            displayName = userId
            status = UserStatusColumn.ACTIVE
            createdAt = 1L
            updatedAt = 1L
            this.canEdit = canEdit
            this.canShare = canShare
            this.deletedAt = deletedAt
            this.timezone = timezone
        }
    }
}

/**
 * Resolves the [UserRole] of a seeded user synchronously, for use as the
 * `roleResolver` of [testAuth]. Unseeded ids resolve to [UserRole.ROOT] — the
 * historic test-auth default — so route tests that send a bearer token without
 * a matching user row keep their all-bypassing principal. Only tests that seed a
 * member/admin via [seedTestUser] and then send that id as the bearer token get
 * a non-ROOT principal, which is exactly what the access-gate tests want.
 */
fun Database.roleOf(userId: String): UserRole =
    transaction(this) {
        UserEntity.findById(userId)?.role?.toContract() ?: UserRole.ROOT
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
 * Returns a [PublicProfileMaintainer] backed by this database, for use in tests that exercise
 * [com.calypsan.listenup.server.services.UserStatsUpdater.onListeningEvent] or
 * [com.calypsan.listenup.server.services.UserStatsUpdater.onPositionFinishedFlip] but don't
 * assert on the public-profiles projection. The maintainer's [PublicProfileMaintainer.refresh]
 * is a structural no-op when the user row doesn't exist in [UserTable] — so tests that don't
 * call [seedTestUser] pay no cost beyond a single SELECT per stats write.
 */
fun Database.noOpPublicProfileMaintainer(): PublicProfileMaintainer {
    val sql = asSqlDatabase()
    return PublicProfileMaintainer(
        sql = sql,
        publicProfileRepo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry()),
    )
}

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
