package com.calypsan.listenup.server

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.bearerAuth
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * Standalone kotlinx.rpc smoke client (runs on the JVM) that exercises the **native** server's full
 * RPC surface end-to-end — anonymous ping/instance, then the auth write loop (setupRoot → login →
 * authed `currentUser`), which proves Argon2/password4j hashing, DB writes, JWT issue+validate and
 * the JWT-gated `/api/rpc/authed` route all work under native-image. Driven by `:server:rpcSmoke`.
 *
 * Pass the base ws URL (e.g. `ws://localhost:8107`); `/api/rpc/public` and `/api/rpc/authed` are appended.
 */
fun main(args: Array<String>) =
    runBlocking {
        val base = (args.firstOrNull() ?: "ws://localhost:8107").removeSuffix("/")
        val anon = HttpClient(CIO) { installKrpc() }
        try {
            val public =
                anon.rpc("$base/api/rpc/public") { rpcConfig { serialization { json() } } }

            println("RPC_SMOKE ping -> ${public.withService<PingService>().ping()}")
            println("RPC_SMOKE instance -> ${public.withService<InstanceService>().getServerInfo()}")

            val auth = public.withService<AuthServicePublic>()
            val setup =
                auth.setupRoot(
                    RegisterRequest(email = "root@native.test", password = "password123!", displayName = "Root"),
                )
            println("RPC_SMOKE setupRoot -> $setup")
            val session = (setup as? AppResult.Success)?.data ?: error("setupRoot failed: $setup")

            val login = auth.login(LoginRequest(email = "root@native.test", password = "password123!"))
            println("RPC_SMOKE login -> ${(login as? AppResult.Success)?.data?.user?.email ?: login}")

            val token = session.accessToken.value
            println("RPC_TOKEN=$token")
            val authedHttp =
                HttpClient(CIO) {
                    installKrpc()
                    install(DefaultRequest) { bearerAuth(token) }
                }
            try {
                val authed =
                    authedHttp.rpc("$base/api/rpc/authed") { rpcConfig { serialization { json() } } }
                println("RPC_SMOKE authed currentUser -> ${authed.withService<AuthServiceAuthed>().currentUser()}")

                // Coverage map: call a no-arg read on each data service. A throw means missing native
                // serialization/reflection metadata for that DTO family; an AppResult (Success/Failure)
                // means the wire round-trip worked.
                suspend fun probe(
                    name: String,
                    call: suspend () -> Any?,
                ) {
                    val outcome =
                        runCatching { call() }.fold(
                            { v -> "OK ${v.toString().take(70)}" },
                            { e -> "ERR ${e::class.simpleName}: ${e.message?.take(120)}" },
                        )
                    println("RPC_COV $name -> $outcome")
                }

                probe("genres") { authed.withService<GenreService>().listGenres() }
                probe("tags") { authed.withService<TagService>().listTags() }
                probe("collections") { authed.withService<CollectionService>().listCollections() }
                probe("shelves") { authed.withService<ShelfService>().listMyShelves() }
                probe("profile") { authed.withService<ProfileService>().getMyProfile() }
                probe("libraries") { authed.withService<LibraryAdminService>().listLibraries() }
                probe("setupStatus") { authed.withService<LibraryAdminService>().getSetupStatus() }
                probe("adminUsers") { authed.withService<AdminUserService>().listUsers() }
                probe("regPolicy") { authed.withService<AdminUserService>().getRegistrationPolicy() }
                probe("serverSettings") { authed.withService<AdminSettingsService>().getServerSettings() }
                probe("currentlyListening") { authed.withService<SocialService>().currentlyListening() }
                // Outbound HTTPS / TLS from native: triggers AudibleClient's HttpClient(CIO) GET to
                // https://api.audible.* — proves the native image can do a TLS handshake (the classic
                // native-image breaker). A geo-redirect / empty result still proves TLS works; an
                // SSLException / "no trusted certificate" would be the failure to fix.
                probe("audibleTLS") { authed.withService<MetadataLookupService>().searchContributorMetadata("Sanderson") }
            } finally {
                authedHttp.close()
            }
            println("RPC_SMOKE OK")
        } finally {
            anon.close()
        }
    }
