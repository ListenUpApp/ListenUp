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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [DevicesViewModel].
 *
 * Covers:
 * - Name precedence: label > deviceName > deviceModel > userAgent > "Unknown device"
 * - Secondary descriptor join (platform/version + client/version), blank-safe
 * - listSessions success → [DevicesUiState.Ready] with resolved rows
 * - revokeDevice success → reload reflects the removed row
 * - listSessions failure → [DevicesUiState.Error]
 *
 * Uses a fake [AuthRepository] (no Mokkery) for hermetic seam-level testing.
 */
class DevicesViewModelTest :
    FunSpec({

        // ========== Pure resolution helpers ==========

        test("name precedence: label > deviceName > deviceModel > userAgent > Unknown device") {
            DevicesViewModel.resolveName("My iPhone", "Simon's iPhone", "iPhone15,2", "UA") shouldBe "My iPhone"
            DevicesViewModel.resolveName(null, "Simon's iPhone", "iPhone15,2", "UA") shouldBe "Simon's iPhone"
            DevicesViewModel.resolveName(null, null, "Pixel 10", "UA") shouldBe "Pixel 10"
            DevicesViewModel.resolveName(null, null, null, "Chrome") shouldBe "Chrome"
            DevicesViewModel.resolveName(null, null, null, null) shouldBe "Unknown device"
            DevicesViewModel.resolveName("  ", null, "Pixel 10", null) shouldBe "Pixel 10"
        }

        test("secondary descriptor joins platform/version + client/version, blank-safe") {
            DevicesViewModel.secondaryOf(
                DeviceInfo(
                    platform = "iOS",
                    platformVersion = "17.2",
                    clientName = "ListenUp",
                    clientVersion = "1.0.0",
                ),
            ) shouldBe "iOS 17.2 · ListenUp 1.0.0"
            DevicesViewModel.secondaryOf(DeviceInfo(platform = "Android")) shouldBe "Android"
            DevicesViewModel.secondaryOf(null) shouldBe ""
        }

        // ========== Load ==========

        test("loads sessions into Ready") {
            runTest {
                val repo =
                    fakeRepo(
                        sessions = listOf(summary(id = "s1", deviceModel = "Pixel 10")),
                    )
                val vm = DevicesViewModel(repo)
                vm.uiState.test {
                    val ready = awaitUntil { it is DevicesUiState.Ready }.shouldBeInstanceOf<DevicesUiState.Ready>()
                    ready.devices.map { it.displayName } shouldContain "Pixel 10"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Revoke ==========

        test("revokeDevice removes the row on success") {
            runTest {
                // First listSessions returns two rows, the second returns one (after revoke).
                val responses =
                    ArrayDeque(
                        listOf(
                            listOf(summary(id = "s1", deviceModel = "Pixel 10"), summary(id = "s2", deviceModel = "iPad")),
                            listOf(summary(id = "s1", deviceModel = "Pixel 10")),
                        ),
                    )
                val repo =
                    object : FakeAuthRepository() {
                        override suspend fun listSessions(): AppResult<List<SessionSummary>> = AppResult.Success(responses.removeFirst())

                        override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> = AppResult.Success(Unit)
                    }
                val vm = DevicesViewModel(repo)
                vm.uiState.test {
                    val initial = awaitUntil { it is DevicesUiState.Ready }.shouldBeInstanceOf<DevicesUiState.Ready>()
                    initial.devices.map { it.displayName } shouldContain "iPad"

                    vm.revokeDevice("s2")

                    val after =
                        awaitUntil { it is DevicesUiState.Ready && it.devices.size == 1 }
                            .shouldBeInstanceOf<DevicesUiState.Ready>()
                    after.devices.map { it.displayName } shouldNotContain "iPad"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Error ==========

        test("load failure → Error") {
            runTest {
                val repo =
                    object : FakeAuthRepository() {
                        override suspend fun listSessions(): AppResult<List<SessionSummary>> = AppResult.Failure(AuthError.SessionExpired())
                    }
                val vm = DevicesViewModel(repo)
                vm.uiState.test {
                    awaitUntil { it is DevicesUiState.Error }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ========== Helpers ==========

private fun summary(
    id: String,
    label: String? = null,
    deviceName: String? = null,
    deviceModel: String? = null,
    userAgent: String? = null,
    current: Boolean = false,
): SessionSummary =
    SessionSummary(
        id = SessionId(id),
        label = label,
        deviceInfo =
            if (deviceName == null && deviceModel == null) {
                null
            } else {
                DeviceInfo(deviceName = deviceName, deviceModel = deviceModel)
            },
        userAgent = userAgent,
        createdAt = 0L,
        lastUsedAt = 0L,
        current = current,
    )

private fun fakeRepo(sessions: List<SessionSummary>): AuthRepository =
    object : FakeAuthRepository() {
        override suspend fun listSessions(): AppResult<List<SessionSummary>> = AppResult.Success(sessions)
    }

/**
 * Open fake [AuthRepository] — every method fails by default so tests override
 * only the surface they exercise.
 */
private open class FakeAuthRepository : AuthRepository {
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

private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
