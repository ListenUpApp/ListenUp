package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.client.data.connection.ConnectionEvidence
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The R1 gate for the RpcChannel migration: pins the three Koin facts the whole one-liner-per-service
 * design rests on, BEFORE any production module adopts the DSL. If any of these regresses, the
 * documented fallback (a secondary explicit `single<RemoteCache>` inside the DSL body) changes only
 * `RpcChannel.kt` — no call sites.
 *
 * The channels never connect: the pin-tests only resolve/instantiate them, and `RpcProxyCache`'s
 * WebSocket is cold until the first `call`/`stream`, so mocked deps suffice.
 */
class RpcChannelKoinTest :
    FunSpec({

        fun testKoin() =
            koinApplication {
                modules(
                    module {
                        single<ApiClientFactory> { mock<ApiClientFactory>() }
                        single<ServerConfig> { mock<ServerConfig>() }
                        single<RpcAuthRecovery> { RpcAuthRecovery.None }
                        single { ConnectionEvidence() }
                        rpcChannel<GenreService>()
                        rpcChannel<TagService>()
                        // A Public-policy channel alongside the Authed ones: pins that Public + the
                        // shared RpcAuthRecovery single construct together WITHOUT a Koin cycle.
                        rpcChannel<AuthServicePublic>(RpcPolicy.Public)
                    },
                )
            }.koin

        test("two channels in one module resolve without qualifier collision, as distinct instances") {
            val koin = testKoin()
            val genre = koin.get<RpcChannel<GenreService>>(rpcChannelQualifier<GenreService>())
            val tag = koin.get<RpcChannel<TagService>>(rpcChannelQualifier<TagService>())
            (genre === tag) shouldBe false
            koin.close()
        }

        test("every channel joins the RemoteCache invalidation sweep via getAll") {
            val koin = testKoin()
            val genre = koin.get<RpcChannel<GenreService>>(rpcChannelQualifier<GenreService>())
            val tag = koin.get<RpcChannel<TagService>>(rpcChannelQualifier<TagService>())

            // The load-bearing empirical proof: a QUALIFIED single with a secondary `binds RemoteCache`
            // type IS collected by getAll<RemoteCache>() — i.e. the invalidator sweep will drop it.
            val sweep = koin.getAll<RemoteCache>()
            sweep shouldContain genre
            sweep shouldContain tag
            koin.close()
        }

        // W7 firewall pin: a Public-policy channel resolves as its own distinct single, constructs
        // without a Koin cycle (Public → RpcAuthRecovery.None, so no lazy AuthRepository chase at
        // build), and still joins the RemoteCache invalidation sweep like every other channel.
        test("a Public-policy channel resolves distinctly and joins the RemoteCache sweep") {
            val koin = testKoin()
            val public = koin.get<RpcChannel<AuthServicePublic>>(rpcChannelQualifier<AuthServicePublic>())
            val genre = koin.get<RpcChannel<GenreService>>(rpcChannelQualifier<GenreService>())

            (public === genre) shouldBe false
            koin.getAll<RemoteCache>() shouldContain public
            koin.close()
        }
    })
