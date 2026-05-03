package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Tests for [LogoutUseCase] — best-effort server revoke + always-clean
 * local logout.
 */
class LogoutUseCaseTest {
    private class TestFixture {
        val authRepository: AuthRepository = mock()
        val authSession: AuthSession = mock()
        val userRepository: UserRepository = mock()

        fun build(): LogoutUseCase =
            LogoutUseCase(
                authRepository = authRepository,
                authSession = authSession,
                userRepository = userRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        everySuspend { fixture.authSession.isAuthenticated() } returns true
        everySuspend { fixture.authSession.clearAuthTokens() } returns Unit
        everySuspend { fixture.userRepository.clearUsers() } returns Unit
        everySuspend { fixture.authRepository.logout() } returns AppResult.Success(Unit)
        return fixture
    }

    @Test
    fun `logout calls server then clears local state`() =
        runTest {
            val fixture = createFixture()
            val useCase = fixture.build()

            val result = useCase()

            assertIs<AppResult.Success<Unit>>(result)
            verifySuspend { fixture.authRepository.logout() }
            verifySuspend { fixture.authSession.clearAuthTokens() }
            verifySuspend { fixture.userRepository.clearUsers() }
        }

    @Test
    fun `logout still clears local state when server returns failure`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.authRepository.logout() } returns
                AppResult.Failure(AuthError.SessionNotFound())
            val useCase = fixture.build()

            val result = useCase()

            assertIs<AppResult.Success<Unit>>(result)
            verifySuspend { fixture.authSession.clearAuthTokens() }
            verifySuspend { fixture.userRepository.clearUsers() }
        }

    @Test
    fun `logout skips server call when not authenticated`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.authSession.isAuthenticated() } returns false
            val useCase = fixture.build()

            val result = useCase()

            assertIs<AppResult.Success<Unit>>(result)
            verifySuspend { fixture.authSession.clearAuthTokens() }
            verifySuspend { fixture.userRepository.clearUsers() }
        }

    @Test
    fun `logoutLocally clears tokens without server call`() =
        runTest {
            val fixture = createFixture()
            val useCase = fixture.build()

            val result = useCase.logoutLocally()

            assertIs<AppResult.Success<Unit>>(result)
            verifySuspend { fixture.authSession.clearAuthTokens() }
            verifySuspend { fixture.userRepository.clearUsers() }
        }
}
