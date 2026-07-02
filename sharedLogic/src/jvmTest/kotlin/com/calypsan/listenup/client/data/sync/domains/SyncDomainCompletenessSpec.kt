package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * The Phase-2 closing invariant: the contract ([SyncDomains.all]), the client
 * descriptor catalog ([syncDomainCatalog]), and the server's registered
 * repositories list exactly the same 20 mirrored domains — 1:1:1. A domain
 * declared on one side and not the others fails this spec before any runtime
 * symptom (a missing SSE stream, an un-bootstrapped table) can appear.
 *
 * The server leg boots the **real** production [module] in a Ktor
 * `testApplication`, mints a ROOT token via `/api/v1/auth/setup`, and reads
 * `GET /api/v1/sync/domains`. That route responds with
 * `DomainList(domains = registry.knownDomains())`, where the registry is
 * populated by every `SqlSyncableRepository` self-registering at bootstrap —
 * so the assertion is against the full production DI graph, not a test
 * re-listing that could drift from it.
 */
class SyncDomainCompletenessSpec :
    FunSpec({

        test("client catalog mirrors SyncDomains.all exactly (1:1, both directions)") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        pingActivity = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )
                val catalogNames = catalog.mirrored.map { it.key.name }

                catalogNames.toSet() shouldHaveSize catalogNames.size
                catalogNames.toSet() shouldBe SyncDomains.all.map { it.name }.toSet()
            } finally {
                db.close()
            }
        }

        test("refreshed tier claims exactly the four fold-candidate controls, distinct and disjoint from engine controls") {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "spec-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        pingActivity = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                val triggers = catalog.refreshed.map { it.trigger }

                // Distinct — no two refreshed domains claim the same control (else the
                // router's KClass map would silently drop one).
                triggers.toSet() shouldHaveSize triggers.size

                // Exactly the four fold-candidate nudges.
                triggers.toSet() shouldBe
                    setOf(
                        SyncControl.ActiveSessionsChanged::class,
                        SyncControl.ActivityChanged::class,
                        SyncControl.ServerInfoChanged::class,
                        SyncControl.PreferencesChanged::class,
                    )

                // Disjoint from the engine/lifecycle controls the dispatcher owns.
                triggers shouldNotContainAnyOf
                    listOf(
                        SyncControl.CursorStale::class,
                        SyncControl.StreamError::class,
                        SyncControl.AccessChanged::class,
                        SyncControl.UserDeleted::class,
                        SyncControl.LibraryDataChanged::class,
                    )
            } finally {
                db.close()
            }
        }

        test("server registrations mirror SyncDomains.all exactly (1:1, both directions)") {
            val homeDir = Files.createTempDirectory("listenup-completeness-home-")
            val tmpDb =
                Files
                    .createTempFile("listenup-completeness-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
            try {
                testApplication {
                    environment {
                        config =
                            MapApplicationConfig(
                                "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                                "auth.refreshPepper" to "x".repeat(REFRESH_PEPPER_LENGTH),
                                "jwt.secret" to "x".repeat(JWT_SECRET_LENGTH),
                                "jwt.issuer" to "listenup",
                                "jwt.audience" to "listenup-client",
                                "registration.policy" to "OPEN",
                                "mdns.enabled" to "false",
                                "listenup.home" to homeDir.toString(),
                            )
                    }
                    application { module() }

                    val client =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }
                    // The sync/domains route is mounted inside authenticate(JWT_PROVIDER),
                    // so it needs a real bearer token — mint the first user as ROOT.
                    val accessToken = client.setupRoot()

                    val domains =
                        client
                            .get("/api/v1/sync/domains") { bearerAuth(accessToken) }
                            .body<DomainList>()
                            .domains

                    domains.toSet() shouldBe SyncDomains.all.map { it.name }.toSet()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/** Registers the first user as ROOT via `/api/v1/auth/setup` and returns the access token. */
private suspend fun HttpClient.setupRoot(): String {
    val result =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "root@completeness.test",
                    password = "password1234",
                    displayName = "Root",
                ),
            )
        }.body<AppResult<AuthSession>>()
    return (result as AppResult.Success<AuthSession>).data.accessToken.value
}

private const val JWT_SECRET_LENGTH = 32
private const val REFRESH_PEPPER_LENGTH = 32

private class StubAvatarDownloadRepository : AvatarDownloadRepository {
    override fun queueAvatarDownload(userId: String) = Unit

    override fun queueAvatarForceRefresh(userId: String) = Unit

    override suspend fun deleteAvatar(userId: String) = Unit
}
