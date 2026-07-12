package com.calypsan.listenup.client.di.e2e

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.DefaultRpcCacheInvalidator
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.Kind

class ClientKoinGraphE2ETest :
    FunSpec({

        test("fixture boots the server and builds the client graph") {
            val fixture = autoClose(DiWiredClientFixture.start())
            // Resolving a known singleton proves the graph wired, not just that start() returned.
            fixture.koin.koin.get<AuthSession>() shouldNotBe null
        }

        test("the client Koin graph instantiates without a constructor cycle") {
            val fixture = autoClose(DiWiredClientFixture.start())
            val koin = fixture.koin.koin

            // `createEagerInstances()` builds every `createdAtStart` singleton —
            // including the sync-domain handlers, where the 2026-05-15 crash lived.
            // Explicit because eager init during startup is the exact path the 2026-05-15
            // crash took; the loop below re-get()s these but Koin returns the cached instance.
            fixture.koin.createEagerInstances()

            // Force the rest: resolve every Singleton definition. A constructor cycle
            // anywhere in the singleton graph throws StackOverflowError here and fails
            // the test — exactly the fault `module.verify()` cannot catch.
            //
            // `koin.instanceRegistry.instances` is a Map<IndexKey, InstanceFactory<*>>
            // where multiple IndexKey entries can point to the same factory (secondary
            // type bindings). Deduplicating by factory identity avoids double-creation.
            //
            // Factory (Kind.Factory) definitions are excluded: they require caller-supplied
            // parameters (parametersOf(...)) and a bare get() would throw a missing-parameter
            // error — not a cycle. Scoped (Kind.Scoped) definitions require an open custom
            // scope, so they are excluded for the same reason. The cycle guard covers the
            // singleton graph, which is where construction-time cycles actually live.
            @OptIn(KoinInternalApi::class)
            val singletonFactories =
                koin.instanceRegistry.instances.values
                    .filter { it.beanDefinition.kind == Kind.Singleton }
                    .toSet() // dedup by object identity: the same factory may be registered under multiple type keys

            for (factory in singletonFactories) {
                val definition = factory.beanDefinition
                try {
                    koin.get<Any>(
                        clazz = definition.primaryType,
                        qualifier = definition.qualifier,
                        parameters = null,
                    )
                } catch (e: StackOverflowError) {
                    // A StackOverflowError here means a constructor cycle — this is the
                    // exact fault we're guarding against. Rethrow to fail the test.
                    throw AssertionError(
                        "Constructor cycle detected while instantiating '${definition.primaryType.simpleName}'. " +
                            "This is the fault class that crash-looped the app on 2026-05-15.",
                        e,
                    )
                } catch (_: Exception) {
                    // Any Exception (NoDefinitionFoundException, InstanceCreationException, …) that is
                    // not a cycle — skip and continue. Non-Exception Errors propagate and fail loudly;
                    // only StackOverflowError (the cycle signal, caught above) is treated specially.
                    // The fixture only loads sharedModules; platform-specific singletons
                    // (DownloadEnqueuer via WorkManager, SecureStorage platform impls, etc.)
                    // are intentionally absent and will surface as missing-dep errors here,
                    // not as cycles.
                }
            }
        }

        test("every RemoteCache RPC factory is enrolled in the invalidation set") {
            val fixture = autoClose(DiWiredClientFixture.start())
            val koin = fixture.koin.koin

            val invalidator = koin.get<RpcCacheInvalidator>()
            val defaultInvalidator = invalidator.shouldBeInstanceOf<DefaultRpcCacheInvalidator>()

            // getAll<RemoteCache>() must return exactly 24: ApiClientFactory + 23 RPC dispatch caches
            // (Ktor*RpcFactory implementations and RpcChannel<S> singles). This pins the count so a
            // silently-dropped `binds arrayOf(RemoteCache::class)` declaration causes an immediate test
            // failure before production code ever misses an invalidation.
            // Note: 24 is the sharedModules count (no platform-only RemoteCache impls on JVM).
            // W7 retired the two dual-mount Auth/Invite factories (−2) in favour of four finer-grained
            // channels — AuthServicePublic, AuthServiceAuthed, InviteServicePublic, InviteService (+4).
            // W8a retired the last PlaybackRpcFactory RemoteCache (−1) — its prepare() surface now rides
            // the already-registered rpcChannel<PlaybackService>() via PlaybackPrepareRepository (which
            // is NOT a RemoteCache, just a wrapper), so 25 → 24.
            // The search bug-fix added rpcChannel<SearchService>() (a NEW channel, no factory retired) for
            // the contributor/series server search that previously 404'd over REST, so 24 → 25.
            defaultInvalidator.caches shouldHaveSize 25
            defaultInvalidator.caches.any { it is ApiClientFactory } shouldBe true
        }

        test("the DI-wired client graph authenticates against the live server") {
            // runBlocking (real wall-clock), not runTest: the auth repo now dispatches through
            // RpcChannel, which bounds each call with withTimeout. That clock is virtual under
            // runTest, which would auto-advance past the 15s bound before the real WebSocket
            // handshake completes and spuriously time the setup/login out.
            runBlocking {
                val fixture = autoClose(DiWiredClientFixture.start())
                val koin = fixture.koin.koin

                val authRepository = koin.get<AuthRepository>()

                // Provision a user through the DI-resolved repo (the server starts
                // empty; `setup` is the first-run root-account path).
                val credentials =
                    RegisterRequest(
                        email = "e2e@listenup.app",
                        password = "e2e-password",
                        displayName = "E2E User",
                    )
                authRepository
                    .setup(credentials)
                    .shouldBeInstanceOf<AppResult.Success<*>>()

                // Log in through the DI-resolved repo, against the live server.
                val result =
                    authRepository.login(
                        LoginRequest(email = credentials.email, password = credentials.password),
                    )

                val session =
                    result
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                        .data
                        .shouldBeInstanceOf<com.calypsan.listenup.api.dto.auth.AuthSession>()
                session.user.email shouldBe credentials.email
            }
        }
    })
