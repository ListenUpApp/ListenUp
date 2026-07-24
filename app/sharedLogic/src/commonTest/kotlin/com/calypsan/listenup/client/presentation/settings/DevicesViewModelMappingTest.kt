package com.calypsan.listenup.client.presentation.settings

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests that [DevicesViewModel] maps [DeviceInfo.deviceType] onto [DeviceRow.deviceType].
 */
class DevicesViewModelMappingTest :
    FunSpec({
        test("DeviceRow.deviceType is populated from DeviceInfo.deviceType") {
            runTest {
                val summary =
                    SessionSummary(
                        id = SessionId("s1"),
                        label = "My phone",
                        deviceInfo = DeviceInfo(deviceType = "phone", platform = "Android"),
                        userAgent = null,
                        createdAt = 1L,
                        lastUsedAt = 2L,
                        current = true,
                    )
                val repo =
                    object : MappingFakeAuthRepository() {
                        override suspend fun listSessions(): AppResult<List<SessionSummary>> = AppResult.Success(listOf(summary))
                    }
                val vm = DevicesViewModel(repo)
                vm.uiState.test {
                    val ready =
                        awaitMappingUntil { it is DevicesUiState.Ready }
                            .shouldBeInstanceOf<DevicesUiState.Ready>()
                    ready.devices.single().deviceType shouldBe "phone"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ========== Helpers ==========

private open class MappingFakeAuthRepository : AuthRepository {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> = fail()

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> = fail()

    override suspend fun setup(request: RegisterRequest): AppResult<AuthSession> = fail()

    override suspend fun logout(): AppResult<Unit> = fail()

    override suspend fun refreshAccessToken(): AppResult<AuthSession> = fail()

    override suspend fun listSessions(): AppResult<List<SessionSummary>> = fail()

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> = fail()

    override suspend fun logoutAll(): AppResult<Unit> = fail()

    private fun <T> fail(): AppResult<T> = AppResult.Failure(AuthError.SessionExpired())
}

private suspend fun <T> ReceiveTurbine<T>.awaitMappingUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
