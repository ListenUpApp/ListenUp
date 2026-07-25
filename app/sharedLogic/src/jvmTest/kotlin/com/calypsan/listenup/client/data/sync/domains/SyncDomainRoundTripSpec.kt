package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.KSerializer
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * The check that licenses carrying sync rows as encoded text.
 *
 * [com.calypsan.listenup.api.sync.SyncPage] has a typed envelope and untyped rows: the server
 * encodes each row with the `KSerializer` its repository holds, and the client decodes it with the
 * one its handler holds. Both sides reach that serializer through the same
 * [com.calypsan.listenup.api.sync.SyncDomains] constant, so the association is *usually* pinned by
 * construction — but nothing in the type system forces a repository to register under a shared
 * constant rather than a hand-rolled `SyncDomainKey`, and nothing forces the two sides to agree on
 * the domain *name* either. That gap is what this spec closes, at CI time.
 *
 * It closes it by round-tripping for real rather than by comparing serializers to themselves: a
 * production server is booted, every domain it registers is pulled over a real RPC proxy, and each
 * returned row is decoded with the *client handler's* serializer. A mismatch surfaces as a decode
 * failure naming the domain.
 *
 * **On vacuity.** A freshly-bootstrapped server holds rows for only a few domains, so most pulls
 * return an empty page and decode nothing. An all-empty run would prove nothing at all, so the
 * spec asserts a floor: at least one domain must have produced rows, and the domains known to be
 * seeded by bootstrap must be among them. Widening that floor as more domains gain bootstrap rows
 * is a deliberate edit here — see [DOMAINS_SEEDED_AT_BOOTSTRAP].
 *
 * Sibling of `SyncDomainCompletenessSpec`, which pins the domain *set* three ways; this pins the
 * domain → payload-type *association* end to end.
 */
class SyncDomainRoundTripSpec :
    FunSpec({

        test("every registered server domain pulls rows the client handler's serializer can decode") {
            val homeDir = Files.createTempDirectory("listenup-roundtrip-home-")
            val tmpDb = Files.createTempFile("listenup-roundtrip-", ".db").toFile().apply { deleteOnExit() }
            val clientDb = createInMemoryTestDatabase()
            try {
                testApplication {
                    environment { config = roundTripConfig(tmpDb.absolutePath, homeDir.toString()) }
                    application { module() }

                    // The real client catalog: the same handlers production registers, each
                    // carrying the payload serializer its domain declares.
                    val registry = ClientSyncDomainRegistry()
                    registerTestSyncDomains(db = clientDb, registry = registry)

                    val token = setupRootReturningToken()
                    val sync = authedSyncService(token)
                    val domains =
                        sync
                            .listDomains()
                            .shouldBeInstanceOf<AppResult.Success<List<String>>>()
                            .data

                    val undecodable = mutableListOf<String>()
                    val domainsWithRows = mutableListOf<String>()

                    for (domain in domains) {
                        val handler =
                            registry.lookup(domain)
                                // A domain the client has no handler for is SyncDomainCompletenessSpec's
                                // failure to report, not this spec's — skip rather than double-fail.
                                ?: continue
                        val page =
                            sync
                                .pullDomain(domain, since = 0, limit = PAGE_LIMIT)
                                .shouldBeInstanceOf<AppResult.Success<SyncPage>>()
                                .data

                        if (page.items.isNotEmpty()) domainsWithRows += domain
                        val serializer: KSerializer<*> = handler.payloadSerializer
                        for (encoded in page.items) {
                            runCatching {
                                contractJson.decodeFromString(serializer, encoded)
                            }.onFailure { undecodable += "$domain: ${it.message}" }
                        }
                    }

                    // The real assertion: no row the server encoded failed the client's decode.
                    undecodable.shouldBeEmpty()

                    // Anti-vacuity floor — see the class KDoc. Without these the loop above would
                    // pass on a server that returned nothing at all for every domain.
                    domainsWithRows.size shouldBeGreaterThan 0
                    domainsWithRows shouldContainAll DOMAINS_SEEDED_AT_BOOTSTRAP
                }
            } finally {
                clientDb.close()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/**
 * Domains a freshly-bootstrapped server is known to hold rows for, so the round trip above has
 * something real to decode. Registering the root user writes the roster and profile rows and the
 * default collection grant.
 *
 * Narrow on purpose: this is the floor that keeps the spec honest, not a claim about what a
 * populated server holds. Add to it only after confirming bootstrap really does seed the domain.
 */
private val DOMAINS_SEEDED_AT_BOOTSTRAP = listOf("public_profiles")

private const val PAGE_LIMIT = 500
private const val JWT_SECRET_LENGTH = 32
private const val REFRESH_PEPPER_LENGTH = 32

private fun roundTripConfig(
    jdbcPath: String,
    homeDir: String,
): MapApplicationConfig =
    MapApplicationConfig(
        "database.jdbcUrl" to "jdbc:sqlite:$jdbcPath",
        "auth.refreshPepper" to "x".repeat(REFRESH_PEPPER_LENGTH),
        "jwt.secret" to "x".repeat(JWT_SECRET_LENGTH),
        "jwt.issuer" to "listenup",
        "jwt.audience" to "listenup-client",
        "registration.policy" to "OPEN",
        "mdns.enabled" to "false",
        "listenup.home" to homeDir,
    )

/** The authed [SyncStreamService] over a real kotlinx.rpc proxy, as [accessToken]'s caller. */
private suspend fun ApplicationTestBuilder.authedSyncService(accessToken: String): SyncStreamService =
    createClient {
        install(WebSockets)
        installKrpc()
    }.rpc("ws://localhost/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
        bearerAuth(accessToken)
    }.withService<SyncStreamService>()

/** Registers the first user as ROOT via `AuthServicePublic.setupRoot` and returns the access token. */
private suspend fun ApplicationTestBuilder.setupRootReturningToken(): String {
    val result =
        createClient {
            install(WebSockets)
            installKrpc()
        }.rpc("ws://localhost/api/rpc/public") {
            rpcConfig { serialization { json(contractJson) } }
        }.withService<AuthServicePublic>()
            .setupRoot(
                RegisterRequest(
                    email = "root@roundtrip.test",
                    password = "password1234",
                    displayName = "Root",
                ),
            )
    return result
        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
        .data.accessToken.value
}
