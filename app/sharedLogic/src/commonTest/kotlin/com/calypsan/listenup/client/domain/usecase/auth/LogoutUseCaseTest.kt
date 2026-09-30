package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.DefaultRpcCacheInvalidator
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.data.remote.RpcConnection
import com.calypsan.listenup.client.data.remote.RpcProxyCache
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LibraryResetHelper
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.order
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** A fake proxy that records which stored token its connection was opened with. */
private data class MintedAs(
    val token: String?,
)

private class LogoutFixture {
    val authRepository: AuthRepository = mock()
    val authSession: AuthSession = mock()
    val userRepository: UserRepository = mock()
    val syncRepository: SyncRepository = mock()
    val rpcCacheInvalidator: RpcCacheInvalidator = mock()
    val libraryResetHelper: LibraryResetHelper = mock()

    fun build(): LogoutUseCase =
        LogoutUseCase(
            authRepository = authRepository,
            authSession = authSession,
            userRepository = userRepository,
            syncRepository = syncRepository,
            rpcCacheInvalidator = rpcCacheInvalidator,
            libraryResetHelper = libraryResetHelper,
        )
}

private fun createFixture(): LogoutFixture {
    val fixture = LogoutFixture()
    everySuspend { fixture.authSession.isAuthenticated() } returns true
    everySuspend { fixture.authSession.clearAuthTokens() } returns Unit
    everySuspend { fixture.userRepository.clearUsers() } returns Unit
    everySuspend { fixture.authRepository.logout() } returns AppResult.Success(Unit)
    everySuspend { fixture.syncRepository.disconnect() } returns Unit
    everySuspend { fixture.rpcCacheInvalidator.invalidateAll() } returns Unit
    everySuspend { fixture.libraryResetHelper.clearLibraryData(discardPendingOperations = true) } returns Unit
    return fixture
}

/**
 * Tests for [LogoutUseCase] — best-effort server revoke + always-clean
 * local logout.
 */
class LogoutUseCaseTest :
    FunSpec({

        test("logout calls server then clears local state") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result = useCase()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { fixture.authRepository.logout() }
                verifySuspend { fixture.authSession.clearAuthTokens() }
                verifySuspend { fixture.userRepository.clearUsers() }
            }
        }

        test("logout still clears local state when server returns failure") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.logout() } returns
                    AppResult.Failure(AuthError.SessionNotFound())
                val useCase = fixture.build()

                val result = useCase()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { fixture.authSession.clearAuthTokens() }
                verifySuspend { fixture.userRepository.clearUsers() }
            }
        }

        test("logout skips server call when not authenticated") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authSession.isAuthenticated() } returns false
                val useCase = fixture.build()

                val result = useCase()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { fixture.authSession.clearAuthTokens() }
                verifySuspend { fixture.userRepository.clearUsers() }
            }
        }

        test("logoutLocally clears tokens without server call") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result = useCase.logoutLocally()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { fixture.authSession.clearAuthTokens() }
                verifySuspend { fixture.userRepository.clearUsers() }
            }
        }

        test("logout stops the sync engine so it can't reconnect against a dead session") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                verifySuspend { fixture.syncRepository.disconnect() }
            }
        }

        test("logout invalidates cached RPC connections so they can't be reused by the next user") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                verifySuspend { fixture.rpcCacheInvalidator.invalidateAll() }
            }
        }

        test("logoutLocally stops the sync engine") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase.logoutLocally()

                verifySuspend { fixture.syncRepository.disconnect() }
            }
        }

        test("logout clears library data including pending operations, discarding unsent edits") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                verifySuspend { fixture.libraryResetHelper.clearLibraryData(discardPendingOperations = true) }
            }
        }

        test("logout stops the sync engine before clearing library data") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                // The engine must be fully stopped before the data it could still be touching
                // (an in-flight SSE apply, an outbox drain) gets wiped out from under it.
                verifySuspend(order) {
                    fixture.syncRepository.disconnect()
                    fixture.libraryResetHelper.clearLibraryData(discardPendingOperations = true)
                }
            }
        }

        test("logout clears library data before clearing auth tokens") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                verifySuspend(order) {
                    fixture.libraryResetHelper.clearLibraryData(discardPendingOperations = true)
                    fixture.authSession.clearAuthTokens()
                }
            }
        }

        // An idempotent read in flight when the first sweep force-closes its connection retries on a
        // FRESH lease — and that connect mints its socket ticket from the token still stored, because
        // tokens are cleared last. The sweep after the clear is what closes that socket; without it the
        // previous user's socket becomes the channel's live connection and serves the next login.
        test("logout sweeps the RPC connections again after the tokens are cleared") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase()

                verifySuspend(order) {
                    fixture.authSession.clearAuthTokens()
                    fixture.rpcCacheInvalidator.invalidateAll()
                }
            }
        }

        test("local-only logout sweeps the RPC connections again after the tokens are cleared") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                useCase.logoutLocally()

                verifySuspend(order) {
                    fixture.authSession.clearAuthTokens()
                    fixture.rpcCacheInvalidator.invalidateAll()
                }
            }
        }

        test("a connection opened mid-logout, on the old token, is never leased after logout") {
            runTest {
                // The real sweep over a real connection cache whose connect "mints" from whatever token is
                // stored at that moment — so each connection remembers the identity it was opened as.
                var storedToken: String? = "user-a"
                val cache =
                    RpcProxyCache(
                        apiClientFactory =
                            mock<ApiClientFactory> {
                                everySuspend { getClient() } calls {
                                    HttpClient(MockEngine { respond("") }) { install(WebSockets) }
                                }
                            },
                        serverConfig = mock<ServerConfig> { everySuspend { getActiveUrl() } returns ServerUrl("http://localhost") },
                    ) { _, _ -> RpcConnection(MintedAs(storedToken)) {} }
                cache.call { it } shouldBe MintedAs("user-a")

                val fixture = createFixture()
                everySuspend { fixture.authSession.clearAuthTokens() } calls { storedToken = null }
                // A retry racing the logout: after the first sweep, before the tokens are cleared, it
                // leases — and connects — afresh on the old token.
                everySuspend { fixture.libraryResetHelper.clearLibraryData(discardPendingOperations = true) } calls {
                    cache.call { it } shouldBe MintedAs("user-a")
                }
                val useCase =
                    LogoutUseCase(
                        authRepository = fixture.authRepository,
                        authSession = fixture.authSession,
                        userRepository = fixture.userRepository,
                        syncRepository = fixture.syncRepository,
                        rpcCacheInvalidator = DefaultRpcCacheInvalidator(caches = listOf(cache)),
                        libraryResetHelper = fixture.libraryResetHelper,
                    )

                useCase()

                // The next user signs in; their first call must ride a connection opened as them.
                storedToken = "user-b"
                cache.call { it } shouldBe MintedAs("user-b")
            }
        }
    })
