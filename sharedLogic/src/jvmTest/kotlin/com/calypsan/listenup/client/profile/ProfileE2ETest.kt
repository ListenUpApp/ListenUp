package com.calypsan.listenup.client.profile

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.testing.testAuth
import com.calypsan.listenup.server.api.createProfileService
import com.calypsan.listenup.server.api.profileServiceScopedTo
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.routes.AVATAR_MAX_BYTES
import kotlinx.io.files.Path as IoPath
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.rpcguard.guard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import app.cash.sqldelight.db.SqlDriver

/**
 * Cross-module E2E: a real `:server` [ProfileService] boots in-process via
 * `testApplication`, and a real kotlinx.rpc client proxy exercises the
 * `updateMyProfile` → `getMyProfile` round-trip over WebSocket RPC.
 *
 * Both the server impl and the transport are exercised end-to-end:
 *  - [createProfileService] constructs the real [com.calypsan.listenup.server.api.ProfileServiceImpl]
 *  - [guard] wraps it with the KSP-generated [com.calypsan.listenup.server.rpcguard.ProfileServiceGuarded] decorator
 *  - `serverRpc("/api/rpc/authed")` mounts it behind the test-auth wall
 *  - The client proxy calls through the live WebSocket transport to hit the real server code
 *
 * This validates the contract layer (serialization round-trips), the service impl
 * (update semantics, null-field-preservation), and the WrongPassword typed-error path —
 * the coverage required for "Update own profile".
 *
 * Avatar upload/serve is an HTTP REST surface, not RPC; it is covered by the
 * server-side route test and intentionally excluded here.
 */
class ProfileE2ETest :
    FunSpec({

        fun setupServerDb(): SqlDriver {
            val tmp = Files.createTempFile("listenup-profile-e2e-", ".db").toFile().apply { deleteOnExit() }
            return DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
        }

        /**
         * Wraps the already-migrated [SqlDriver] in a SQLDelight [ServerSqlDatabase]. Migrations have
         * already run via [DatabaseFactory.init], so the database never calls `Schema.create`; the
         * driver is the single read/write view over the one file, exactly as production wires it.
         */
        fun SqlDriver.asServerSqlDatabase(): ServerSqlDatabase = ServerSqlDatabase(this)

        /**
         * Seeds a minimal user row via raw SQL.
         *
         * [com.calypsan.listenup.server.testing.seedTestUser] lives in `:server`'s test source set
         * and is not available from `:sharedLogic:jvmTest`. Raw SQL is the established pattern here —
         * see `WithCollectionSyncEngineAgainstServer` for precedent.
         */
        fun SqlDriver.seedUser(
            userId: String,
            passwordHash: String = "phc",
        ) {
            val now = System.currentTimeMillis()
            execute(
                null,
                "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES " +
                    "('$userId', '$userId@example.com', '$userId@example.com', '$passwordHash', " +
                    "'MEMBER', '$userId', 'ACTIVE', $now, $now)",
                0,
            )
        }

        test("updateMyProfile then getMyProfile returns updated displayName and tagline") {
            val serverDriver = setupServerDb()
            serverDriver.seedUser("u1")
            // PublicProfileMaintainer/PublicProfileRepository are SQLDelight-converted: open a
            // The same migrated SQLite file is read/written through the [serverDriver].
            val serverSqlDb = serverDriver.asServerSqlDatabase()
            val profileService =
                createProfileService(
                    sql = serverSqlDb,
                    passwordHasher = PasswordHasher(),
                    publicProfileMaintainer =
                        PublicProfileMaintainer(
                            serverSqlDb,
                            PublicProfileRepository(serverSqlDb, ChangeBus(), SyncRegistry()),
                        ),
                    imageStore = ImageStore(IoPath(Files.createTempDirectory("e2e-avatars").toString()), AVATAR_MAX_BYTES),
                )

            testApplication {
                application {
                    install(Authentication) { testAuth(defaultUserId = "u1") }
                    install(ServerKrpc)
                    routing {
                        authenticate(JWT_PROVIDER) {
                            serverRpc("/api/rpc/authed") {
                                rpcConfig { serialization { krpcJson(contractJson) } }
                                registerService<ProfileService> {
                                    val p = call.userPrincipalOrNull() ?: error("auth wall regression")
                                    guard(profileServiceScopedTo(profileService, PrincipalProvider { p }))
                                }
                            }
                        }
                    }
                }

                val rpcClient = createClient { installKrpc() }
                val proxy = TestProfileRpcFactory(rpcClient).get()

                runTest {
                    val updateResult =
                        proxy.updateMyProfile(
                            UpdateProfileRequest(displayName = "E2E Name", tagline = "hello"),
                        )
                    updateResult.shouldBeInstanceOf<AppResult.Success<*>>()

                    val getResult = proxy.getMyProfile()
                    getResult.shouldBeInstanceOf<AppResult.Success<*>>()
                    val profile = (getResult as AppResult.Success).data
                    profile.displayName shouldBe "E2E Name"
                    profile.tagline shouldBe "hello"
                }
            }
        }

        test("updateMyProfile with wrong current password returns ProfileError.WrongPassword") {
            val serverDriver = setupServerDb()
            val hasher = PasswordHasher()
            // Seed the user with a real Argon2 hash so passwordHasher.verify() can succeed or fail.
            val realHash = hasher.hash("correct-pass")
            serverDriver.seedUser("u2", passwordHash = realHash)
            // PublicProfileMaintainer/PublicProfileRepository are SQLDelight-converted: open a
            // The same migrated SQLite file is read/written through the [serverDriver].
            val serverSqlDb = serverDriver.asServerSqlDatabase()
            val profileService =
                createProfileService(
                    sql = serverSqlDb,
                    passwordHasher = hasher,
                    publicProfileMaintainer =
                        PublicProfileMaintainer(
                            serverSqlDb,
                            PublicProfileRepository(serverSqlDb, ChangeBus(), SyncRegistry()),
                        ),
                    imageStore = ImageStore(IoPath(Files.createTempDirectory("e2e-avatars").toString()), AVATAR_MAX_BYTES),
                )

            testApplication {
                application {
                    install(Authentication) { testAuth(defaultUserId = "u2") }
                    install(ServerKrpc)
                    routing {
                        authenticate(JWT_PROVIDER) {
                            serverRpc("/api/rpc/authed") {
                                rpcConfig { serialization { krpcJson(contractJson) } }
                                registerService<ProfileService> {
                                    val p = call.userPrincipalOrNull() ?: error("auth wall regression")
                                    guard(profileServiceScopedTo(profileService, PrincipalProvider { p }))
                                }
                            }
                        }
                    }
                }

                val rpcClient = createClient { installKrpc() }
                val proxy = TestProfileRpcFactory(rpcClient).get()

                runTest {
                    val result =
                        proxy.updateMyProfile(
                            UpdateProfileRequest(
                                password =
                                    PasswordChange(
                                        currentPassword = "wrong-pass",
                                        newPassword = "brand-new-pass",
                                    ),
                            ),
                        )
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ProfileError.WrongPassword>()
                }
            }
        }
    })

/**
 * Test-only factory that opens a [ProfileService] proxy against the harness's in-process
 * `testApplication` at `ws://localhost/api/rpc/authed`.
 *
 * Mirrors [com.calypsan.listenup.client.data.sync.testing.TestBookRpcFactory]: the proxy
 * is cached after first use; the underlying [io.ktor.client.HttpClient] is the same one
 * supplied by the test via `createClient { installKrpc() }`.
 */
private class TestProfileRpcFactory(
    private val httpClient: io.ktor.client.HttpClient,
) {
    private val mutex = Mutex()
    private var cachedService: ProfileService? = null

    suspend fun get(): ProfileService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    private suspend fun connect(): ProfileService =
        httpClient
            .rpc("ws://localhost/api/rpc/authed") {
                rpcConfig { serialization { krpcJson(contractJson) } }
            }.withService<ProfileService>()
}
