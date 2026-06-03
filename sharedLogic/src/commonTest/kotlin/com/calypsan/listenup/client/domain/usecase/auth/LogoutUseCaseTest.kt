package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class LogoutFixture {
    val authRepository: AuthRepository = mock()
    val authSession: AuthSession = mock()
    val userRepository: UserRepository = mock()
    val syncRepository: SyncRepository = mock()
    val rpcCacheInvalidator: RpcCacheInvalidator = mock()

    fun build(): LogoutUseCase =
        LogoutUseCase(
            authRepository = authRepository,
            authSession = authSession,
            userRepository = userRepository,
            syncRepository = syncRepository,
            rpcCacheInvalidator = rpcCacheInvalidator,
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
    })
