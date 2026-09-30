package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LibraryResetHelper
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.test.runTest

/** A fake proxy that records which stored token its connection was opened with. */
private data class MintedAs(
    val token: String?,
)

/**
 * Logout against a real connection cache and the real identity sweep: an idempotent read the first
 * sweep force-closes retries at once on a FRESH connection — opened on the token still stored, since
 * tokens are cleared last. Nothing sweeps at login, so unless logout sweeps again after the clear,
 * that connection serves the next user.
 */
class LogoutConnectionSweepTest :
    FunSpec({
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

                val authSession =
                    mock<AuthSession> {
                        everySuspend { isAuthenticated() } returns true
                        everySuspend { clearAuthTokens() } calls { storedToken = null }
                    }
                val useCase =
                    LogoutUseCase(
                        authRepository = mock<AuthRepository> { everySuspend { logout() } returns AppResult.Success(Unit) },
                        authSession = authSession,
                        userRepository = mock<UserRepository> { everySuspend { clearUsers() } returns Unit },
                        syncRepository = mock<SyncRepository> { everySuspend { disconnect() } returns Unit },
                        rpcCacheInvalidator = DefaultRpcCacheInvalidator(caches = listOf(cache)),
                        libraryResetHelper =
                            mock<LibraryResetHelper> {
                                // A retry racing the logout: after the first sweep, before the tokens are
                                // cleared, it leases — and connects — afresh on the old token.
                                everySuspend { clearLibraryData(discardPendingOperations = true) } calls {
                                    cache.call { it } shouldBe MintedAs("user-a")
                                }
                            },
                    )

                useCase()

                // The next user signs in; their first call must ride a connection opened as them.
                storedToken = "user-b"
                cache.call { it } shouldBe MintedAs("user-b")
            }
        }
    })
