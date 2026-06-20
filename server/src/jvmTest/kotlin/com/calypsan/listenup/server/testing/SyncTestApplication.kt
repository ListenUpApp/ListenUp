package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.routes.playbackRoutes
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.UserScopedFixtureRepository
import com.calypsan.listenup.server.sync.UserScopedFixtureTable
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Test scope exposing the JSON-capable [client] and the wired repositories to
 * test bodies. Decouples test code from
 * [io.ktor.server.testing.ApplicationTestBuilder] so the overloads of
 * [withTestApplication] are unambiguous.
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
    val client: HttpClient,
    val tagRepo: TagRepository,
    val db: Database,
    /** SQLDelight view over the same file as [db] — the engine [tagRepo] and other converted repos run on. */
    val sqlDb: ListenUpDatabase,
    private val userScopedRepoOrNull: UserScopedFixtureRepository?,
    private val playbackPositionRepoOrNull: PlaybackPositionRepository?,
    private val listeningEventRepoOrNull: ListeningEventRepository?,
    private val userStatsRepoOrNull: UserStatsRepository?,
    private val bookRepoOrNull: BookRepository?,
) {
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
 * Minimal Ktor test harness for Sync Foundation route tests.
 *
 * Constructs an isolated SQLite database (same temp-file pattern as
 * [withInMemoryDatabase]), wires a small Koin module with `Database`,
 * `ChangeBus`, and a global [TagRepository], installs `ContentNegotiation`,
 * `SSE`, and a test [Authentication] provider, then mounts [syncRoutes] inside
 * `authenticate(JWT_PROVIDER)` — mirroring production, where the sync routes
 * are auth-gated.
 *
 * The [testAuth] provider authenticates every request without a real JWT: it
 * derives the user id from an `Authorization: Bearer <token>` header when one
 * is present (the token is the user id verbatim) and falls back to
 * `test-user` otherwise. Tests that send no header authenticate as
 * `test-user`; tests exercising per-user scoping send `bearerAuth("u1")` /
 * `bearerAuth("u2")`.
 *
 * @param userScoped when true, also wires a per-user [UserScopedFixtureRepository]
 *   (domain `user_scoped_fixtures`) and exposes it as [SyncTestScope.userScopedRepo].
 *   Off by default so domain-list and global-domain tests see only `tags`.
 * @param playbackPositions when true, also wires a [PlaybackPositionRepository]
 *   (domain `playback_positions`) and exposes it as [SyncTestScope.playbackPositionRepo].
 *   The table is created by Flyway migrations, so no manual `SchemaUtils.create` is needed.
 * @param playbackEvents when true, also wires [ListeningEventRepository], [UserStatsRepository],
 *   [UserStatsUpdater], and [PlaybackServiceImpl], mounts [playbackRoutes], and exposes
 *   [SyncTestScope.listeningEventRepo] and [SyncTestScope.userStatsRepo]. Enables end-to-end
 *   tests of the `POST /api/v1/playback/events` → stats materialization → sync catch-up path.
 */
internal fun withTestApplication(
    heartbeatIntervalMillis: Long? = null,
    userScoped: Boolean = false,
    playbackPositions: Boolean = false,
    playbackEvents: Boolean = false,
    block: suspend SyncTestScope.() -> Unit,
) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-sync-test-", ".db").toFile().apply { deleteOnExit() }
        val db =
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).database
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val tagRepo = TagRepository(db.asSqlDatabase(), bus, registry)
        val userScopedRepo = if (userScoped) buildUserScopedFixtureRepo(db, bus, registry) else null
        val playbackPositionRepo =
            if (playbackPositions) PlaybackPositionRepository(db.asSqlDatabase(), bus, registry) else null

        // Playback P2: events + stats. The lazy provider breaks the UserStatsRepository ↔
        // UserStatsUpdater mutual reference — same pattern as the production Koin binding.
        val listeningEventRepo: ListeningEventRepository?
        val userStatsRepo: UserStatsRepository?
        val playbackService: PlaybackService?
        var bookRepoForScope: BookRepository? = null
        if (playbackEvents) {
            lateinit var updater: UserStatsUpdater
            val statsRepo =
                UserStatsRepository(
                    db = db.asSqlDatabase(),
                    bus = bus,
                    registry = registry,
                    userStatsUpdaterProvider = { updater },
                )
            val publicProfileMaintainer = buildPublicProfileMaintainer(db = db, bus = bus, registry = registry)
            val eventRepo =
                ListeningEventRepository(
                    db = db.asSqlDatabase(),
                    bus = bus,
                    registry = registry,
                    userStatsUpdater =
                        UserStatsUpdater(
                            sql = db.asSqlDatabase(),
                            db = db,
                            userStatsRepo = statsRepo,
                            publicProfileMaintainerProvider = { publicProfileMaintainer },
                        ).also { updater = it },
                )
            val positionRepoForPlayback = PlaybackPositionRepository(db.asSqlDatabase(), bus, SyncRegistry())
            val signer = AudioUrlSigner(AudioUrlSigner.deriveSigningKey("x".repeat(32)))
            val bookRepo = buildPlaybackBookRepository(db, bus)
            bookRepoForScope = bookRepo
            // Seed the library + folder a test book FKs to, so playback-event tests
            // can upsert a real (accessible) book for the access gate to admit.
            seedPlaybackTestLibrary(db)
            playbackService =
                PlaybackServiceImpl(
                    bookRepository = bookRepo,
                    audioFileLocator = AudioFileLocator(db),
                    audioUrlSigner = signer,
                    playbackPositionRepository = positionRepoForPlayback,
                    listeningEventRepository = eventRepo,
                    userStatsRepository = statsRepo,
                    accessPolicy = BookAccessPolicy(db),
                    principal = PrincipalProvider { error("unscoped — copyWith required") },
                    db = db,
                )
            listeningEventRepo = eventRepo
            userStatsRepo = statsRepo
        } else {
            listeningEventRepo = null
            userStatsRepo = null
            playbackService = null
        }

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(SSE)
            if (playbackEvents) install(Resources)
            install(Authentication) { testAuth() }
            install(Koin) {
                modules(
                    module {
                        single { db }
                        single { bus }
                        single { registry }
                        single(createdAtStart = true) { tagRepo }
                        if (userScopedRepo != null) single(createdAtStart = true) { userScopedRepo }
                        if (playbackPositionRepo != null) single(createdAtStart = true) { playbackPositionRepo }
                        if (listeningEventRepo != null) single(createdAtStart = true) { listeningEventRepo }
                        if (userStatsRepo != null) single(createdAtStart = true) { userStatsRepo }
                    },
                )
            }
            routing {
                authenticate(JWT_PROVIDER) {
                    if (heartbeatIntervalMillis != null) {
                        syncRoutes(heartbeatIntervalMillis = heartbeatIntervalMillis)
                    } else {
                        syncRoutes()
                    }
                    if (playbackService != null) playbackRoutes(playbackService)
                }
            }
        }

        val jsonClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(io.ktor.client.plugins.sse.SSE)
            }

        SyncTestScope(
            client = jsonClient,
            tagRepo = tagRepo,
            db = db,
            sqlDb = db.asSqlDatabase(),
            userScopedRepoOrNull = userScopedRepo,
            playbackPositionRepoOrNull = playbackPositionRepo,
            listeningEventRepoOrNull = listeningEventRepo,
            userStatsRepoOrNull = userStatsRepo,
            bookRepoOrNull = bookRepoForScope,
        ).block()
    }
}

/**
 * Builds the SQLDelight-backed [BookRepository] the playback-events fixture wires, over a fresh
 * shared [SyncRegistry]. Extracted from [withTestApplication] to keep that function within the
 * detekt size budget; mirrors the production wiring (SQLDelight handle for the book aggregate +
 * contributors/series, the Exposed handle for the not-yet-converted collaborators).
 */
private fun buildPlaybackBookRepository(
    db: Database,
    bus: ChangeBus,
): BookRepository {
    val sharedRegistry = SyncRegistry()
    return BookRepository(
        db = db.asSqlDatabase(),
        exposedDb = db,
        bus = bus,
        registry = sharedRegistry,
        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, sharedRegistry),
        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, sharedRegistry),
        genreRepository = GenreRepository(db.asSqlDatabase(), bus, sharedRegistry),
    )
}

/** Creates and schema-initialises a [UserScopedFixtureRepository] for the given test database. */
private suspend fun buildUserScopedFixtureRepo(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
): UserScopedFixtureRepository =
    UserScopedFixtureRepository(db, bus, registry).also {
        suspendTransaction(db) { SchemaUtils.create(UserScopedFixtureTable) }
    }

/**
 * Constructs a [PublicProfileMaintainer] wired to the shared [bus]/[registry].
 * Extracted from [withTestApplication] to keep that function within the size budget.
 */
private fun buildPublicProfileMaintainer(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
): PublicProfileMaintainer {
    val repo = PublicProfileRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
    return PublicProfileMaintainer(sql = db.asSqlDatabase(), db = db, publicProfileRepo = repo)
}

/**
 * Seeds the `test-library` / `test-folder` rows a playback-event test book FKs to.
 * Kept out of [withTestApplication] so that function stays within the size budget.
 */
private suspend fun seedPlaybackTestLibrary(db: Database) {
    val seedNow = System.currentTimeMillis()
    suspendTransaction(db) {
        exec(
            "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                "VALUES ('test-library', 'Test Library', $seedNow, $seedNow, 0)",
        )
        exec(
            "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
                "VALUES ('test-folder', 'test-library', '/tmp/test-library', $seedNow, $seedNow, 0)",
        )
    }
}
