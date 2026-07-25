package com.calypsan.listenup.server.testing

import io.ktor.server.testing.ApplicationTestBuilder

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.UserScopedFixtureRepository
import com.calypsan.listenup.server.sync.createSyncStreamService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.resources.Resources
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.sqlite.SQLiteConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Test scope exposing the pull surface via [syncService] and the wired repositories to test
 * bodies. Decouples test code from [io.ktor.server.testing.ApplicationTestBuilder] so the
 * overloads of [withTestApplication] are unambiguous.
 *
 * [userScopedRepo] is the per-user fixture domain (`user_scoped_fixtures`); it
 * is wired only when [withTestApplication] is called with `userScoped = true`,
 * and accessing it otherwise throws.
 *
 * [playbackPositionRepo] is the real per-user playback-positions domain; it is
 * wired only when [withTestApplication] is called with `playbackPositions = true`,
 * and accessing it otherwise throws.
 *
 * [listeningEventRepo] and [userStatsRepo] are the playback P2 domains; they are
 * wired only when [withTestApplication] is called with `playbackEvents = true`,
 * and accessing them otherwise throws.
 */
internal data class SyncTestScope(
    private val syncServiceFor: (String) -> SyncStreamService,
    val tagRepo: TagRepository,
    /** The server-side change bus; tests observe the live tail via [rpcFirehose] over it. */
    val bus: ChangeBus,
    /** The SQLDelight database the wired repositories run on; also for direct seed helpers. */
    val sqlDb: ListenUpDatabase,
    private val userScopedRepoOrNull: UserScopedFixtureRepository?,
    private val playbackPositionRepoOrNull: PlaybackPositionRepository?,
    private val listeningEventRepoOrNull: ListeningEventRepository?,
    private val userStatsRepoOrNull: UserStatsRepository?,
    private val bookRepoOrNull: BookRepository?,
) {
    /**
     * The pull surface (`pullDomain` / `pullByIds` / `digest` / `listDomains`) as [userId] sees
     * it — the production [SyncStreamService], scoped to that caller's principal.
     *
     * The caller's role comes from the harness's `roleResolver`, so a test exercising an access
     * gate gets the same `(userId, role)` pair the read-time filter would see in production.
     * Only the transport is absent; the RPC mount itself is covered by the client-side e2e
     * harness in `:app:sharedLogic`.
     */
    fun syncService(userId: String = "test-user"): SyncStreamService = syncServiceFor(userId)

    /**
     * The server book repository, wired only with `playbackEvents = true`. A
     * `test-library` / `test-folder` pair is pre-seeded, so tests can
     * `bookRepo.upsert(...)` a book the playback access gate will admit.
     */
    val bookRepo: BookRepository
        get() =
            requireNotNull(bookRepoOrNull) {
                "bookRepo requires withTestApplication(playbackEvents = true)"
            }

    val userScopedRepo: UserScopedFixtureRepository
        get() =
            requireNotNull(userScopedRepoOrNull) {
                "userScopedRepo requires withTestApplication(userScoped = true)"
            }

    val playbackPositionRepo: PlaybackPositionRepository
        get() =
            requireNotNull(playbackPositionRepoOrNull) {
                "playbackPositionRepo requires withTestApplication(playbackPositions = true)"
            }

    val listeningEventRepo: ListeningEventRepository
        get() =
            requireNotNull(listeningEventRepoOrNull) {
                "listeningEventRepo requires withTestApplication(playbackEvents = true)"
            }

    val userStatsRepo: UserStatsRepository
        get() =
            requireNotNull(userStatsRepoOrNull) {
                "userStatsRepo requires withTestApplication(playbackEvents = true)"
            }
}

/**
 * Minimal test harness for the Sync Foundation pull surface.
 *
 * Constructs an isolated SQLite database (same temp-file pattern as
 * [withSqlDatabase]): [DatabaseFactory.init] migrates the schema, then a SQLDelight
 * [SqlDriver] + [ListenUpDatabase] are opened over the same file. It wires a small
 * Koin module with the SQLDelight database, `ChangeBus`, and a global [TagRepository],
 * and hands the test body a [SyncTestScope] whose [SyncTestScope.syncService] serves the
 * production [SyncStreamService] scoped to any caller the test names.
 *
 * There is no HTTP surface: catch-up, digest and domain discovery ride the RPC socket, so a
 * test names its caller directly (`syncService("u1")`) instead of sending a bearer token.
 * Callers default to [UserRole.ROOT] — the all-bypassing behaviour most tests rely on; pass
 * [roleResolver] to exercise an access gate against a real `(userId, role)` pair.
 *
 * @param userScoped when true, also wires a per-user [UserScopedFixtureRepository]
 *   (domain `user_scoped_fixtures`) and exposes it as [SyncTestScope.userScopedRepo].
 *   Off by default so domain-list and global-domain tests see only `tags`.
 * @param playbackPositions when true, also wires a [PlaybackPositionRepository]
 *   (domain `playback_positions`) and exposes it as [SyncTestScope.playbackPositionRepo].
 *   The table is created by Flyway migrations, so no manual schema creation is needed.
 * @param playbackEvents when true, also wires [ListeningEventRepository], [UserStatsRepository],
 *   [UserStatsUpdater], and exposes [SyncTestScope.listeningEventRepo] and
 *   [SyncTestScope.userStatsRepo]. Enables end-to-end tests of the
 *   record-event → stats materialization → sync catch-up path.
 * @param roleResolver maps an authenticated user id to its [UserRole]; the default grants
 *   every caller [UserRole.ROOT].
 */
internal fun withTestApplication(
    userScoped: Boolean = false,
    playbackPositions: Boolean = false,
    playbackEvents: Boolean = false,
    roleResolver: (String) -> UserRole = { UserRole.ROOT },
    block: suspend SyncTestScope.() -> Unit,
) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-sync-test-", ".db").toFile().apply { deleteOnExit() }
        val path = tmp.absolutePath
        // DatabaseFactory.init runs the Flyway migrations against the file; the SQLDelight
        // driver opened next reads that already-migrated schema (it never calls Schema.create).
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path"))
        val driver = newTestSqlDriver(path)
        val sqlDb = ListenUpDatabase(driver)
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val tagRepo = TagRepository(sqlDb, bus, registry)
        val userScopedRepo = if (userScoped) buildUserScopedFixtureRepo(sqlDb, driver, bus, registry) else null
        val playbackPositionRepo =
            if (playbackPositions) PlaybackPositionRepository(sqlDb, bus, registry) else null

        // Playback P2: events + stats. The lazy provider breaks the UserStatsRepository ↔
        // UserStatsUpdater mutual reference — same pattern as the production Koin binding.
        val listeningEventRepo: ListeningEventRepository?
        val userStatsRepo: UserStatsRepository?
        var bookRepoForScope: BookRepository? = null
        if (playbackEvents) {
            lateinit var updater: UserStatsUpdater
            val statsRepo =
                UserStatsRepository(
                    db = sqlDb,
                    bus = bus,
                    registry = registry,
                    userStatsUpdaterProvider = { updater },
                )
            val publicProfileMaintainer = buildPublicProfileMaintainer(sqlDb, bus, registry)
            // Still constructed for UserStatsRepository's lazy window-decay provider above —
            // the event-driven write cascade now lives entirely in StatsRecorder below.
            updater =
                UserStatsUpdater(
                    sql = sqlDb,
                    userStatsRepo = statsRepo,
                )
            val statsRecorder = buildStatsRecorder(sqlDb, driver, bus, registry, statsRepo, publicProfileMaintainer)
            val eventRepo =
                ListeningEventRepository(
                    db = sqlDb,
                    bus = bus,
                    registry = registry,
                    statsRecorder = statsRecorder,
                )
            bookRepoForScope = buildPlaybackBookRepository(sqlDb, driver, bus)
            // Seed the library + folder a test book FKs to, so playback-event tests
            // can upsert a real (accessible) book for the access gate to admit.
            sqlDb.seedTestLibraryAndFolder()
            listeningEventRepo = eventRepo
            userStatsRepo = statsRepo
        } else {
            listeningEventRepo = null
            userStatsRepo = null
        }

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            if (playbackEvents) install(Resources)
            install(Authentication) { testAuth() }
            install(Koin) {
                modules(
                    module {
                        single { sqlDb }
                        single { bus }
                        single { registry }
                        // The sync route lazily injects BookAccessPolicy to access-filter gated domains
                        // (books/activities/collections). Ungated tag tests never resolve it; a request to
                        // a gated domain (e.g. activities ?bookIds=) does, so provide it here.
                        single { BookAccessPolicy(sqlDb, driver) }
                        single(createdAtStart = true) { tagRepo }
                        if (userScopedRepo != null) single(createdAtStart = true) { userScopedRepo }
                        if (playbackPositionRepo != null) single(createdAtStart = true) { playbackPositionRepo }
                        if (listeningEventRepo != null) single(createdAtStart = true) { listeningEventRepo }
                        if (userStatsRepo != null) single(createdAtStart = true) { userStatsRepo }
                    },
                )
            }
        }

        val bookAccessPolicy = BookAccessPolicy(sqlDb, driver)

        try {
            SyncTestScope(
                syncServiceFor = { userId ->
                    createSyncStreamService(
                        bus = bus,
                        registry = registry,
                        bookAccessPolicy = { bookAccessPolicy },
                        principal =
                            PrincipalProvider {
                                UserPrincipal(
                                    userId = UserId(userId),
                                    sessionId = SessionId("test-session-$userId"),
                                    role = roleResolver(userId),
                                )
                            },
                    )
                },
                tagRepo = tagRepo,
                bus = bus,
                sqlDb = sqlDb,
                userScopedRepoOrNull = userScopedRepo,
                playbackPositionRepoOrNull = playbackPositionRepo,
                listeningEventRepoOrNull = listeningEventRepo,
                userStatsRepoOrNull = userStatsRepo,
                bookRepoOrNull = bookRepoForScope,
            ).block()
        } finally {
            driver.close()
        }
    }
}

/**
 * Opens a SQLDelight [SqlDriver] over the already-migrated [path], enforcing foreign keys +
 * busy timeout + WAL via JDBC connection properties — the same config [withSqlDatabase] uses
 * (a one-time `PRAGMA` is lost because [JdbcSqliteDriver] opens a connection per operation).
 */
private fun newTestSqlDriver(path: String): SqlDriver =
    JdbcSqliteDriver(
        "jdbc:sqlite:$path",
        SQLiteConfig()
            .apply {
                enforceForeignKeys(true)
                busyTimeout = 5000
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            }.toProperties(),
    )

/**
 * Builds the SQLDelight-backed [BookRepository] the playback-events fixture wires, over a fresh
 * shared [SyncRegistry]. Extracted from [withTestApplication] to keep that function within the
 * detekt size budget; mirrors the production wiring (one SQLDelight handle for the book aggregate
 * plus contributors/series/genres, and the access-filter [SqlDriver]).
 */
private fun buildPlaybackBookRepository(
    sqlDb: ListenUpDatabase,
    driver: SqlDriver,
    bus: ChangeBus,
): BookRepository {
    val sharedRegistry = SyncRegistry()
    return BookRepository(
        db = sqlDb,
        driver = driver,
        bus = bus,
        registry = sharedRegistry,
        contributorRepository = ContributorRepository(sqlDb, bus, sharedRegistry),
        seriesRepository = SeriesRepository(sqlDb, bus, sharedRegistry),
        genreRepository = GenreRepository(sqlDb, bus, sharedRegistry),
    )
}

/** Creates a [UserScopedFixtureRepository] and materialises its test-only table. */
private fun buildUserScopedFixtureRepo(
    sqlDb: ListenUpDatabase,
    driver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
): UserScopedFixtureRepository = UserScopedFixtureRepository(sqlDb, driver, bus, registry).apply { createSchema() }

/**
 * Constructs a [PublicProfileMaintainer] wired to the shared [bus]/[registry].
 * Extracted from [withTestApplication] to keep that function within the size budget.
 */
private fun buildPublicProfileMaintainer(
    sqlDb: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
): PublicProfileMaintainer {
    val repo = PublicProfileRepository(db = sqlDb, bus = bus, registry = registry)
    return PublicProfileMaintainer(sql = sqlDb, publicProfileRepo = repo)
}

/**
 * Constructs a [StatsRecorder] wired to the shared [bus] and [statsRepo]/[publicProfileMaintainer].
 * Extracted from [withTestApplication] to keep that function within the size budget.
 */
private fun buildStatsRecorder(
    sqlDb: ListenUpDatabase,
    driver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
    statsRepo: UserStatsRepository,
    publicProfileMaintainer: PublicProfileMaintainer,
): StatsRecorder =
    StatsRecorder(
        sql = sqlDb,
        userStatsRepo = statsRepo,
        bookReadsRepository = BookReadsRepository(db = sqlDb),
        publicProfileMaintainer = publicProfileMaintainer,
        // The activities domain registers in the harness's SHARED registry (via ActivitySyncRepository)
        // and rides the shared driver, so it participates in the app's sync catch-up/digest/firehose.
        activityRecorder =
            ActivityRecorder(
                syncRepo = ActivitySyncRepository(db = sqlDb, bus = bus, registry = registry, driver = driver),
            ),
        statsBackfill = UserStatsBackfillService(sql = sqlDb, userStatsRepo = statsRepo),
    )
