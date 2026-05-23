package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.services.PlaybackPositionRepository
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
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
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
 */
internal data class SyncTestScope(
    val client: HttpClient,
    val tagRepo: TagRepository,
    private val userScopedRepoOrNull: UserScopedFixtureRepository?,
    private val playbackPositionRepoOrNull: PlaybackPositionRepository?,
) {
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
 */
internal fun withTestApplication(
    heartbeatIntervalMillis: Long? = null,
    userScoped: Boolean = false,
    playbackPositions: Boolean = false,
    block: suspend SyncTestScope.() -> Unit,
) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-sync-test-", ".db").toFile().apply { deleteOnExit() }
        val db =
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val tagRepo = TagRepository(db, bus, registry)
        val userScopedRepo =
            if (userScoped) {
                UserScopedFixtureRepository(db, bus, registry).also {
                    suspendTransaction(db) { SchemaUtils.create(UserScopedFixtureTable) }
                }
            } else {
                null
            }
        val playbackPositionRepo =
            if (playbackPositions) PlaybackPositionRepository(db, bus, registry) else null

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(SSE)
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
            userScopedRepoOrNull = userScopedRepo,
            playbackPositionRepoOrNull = playbackPositionRepo,
        ).block()
    }
}
