package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [UserPreferencesRepositoryImpl].
 *
 * Verifies the DTO → domain mapping on read, the single-field PATCH semantics on write, and that a
 * thrown RPC transport error is folded into an [AppResult.Failure] (the data layer never throws).
 */
class UserPreferencesRepositoryImplTest :
    FunSpec({
        fun fixture(service: UserPreferencesService): UserPreferencesRepositoryImpl {
            val factory = mock<UserPreferencesRpcFactory> { everySuspend { get() } returns service }
            return UserPreferencesRepositoryImpl(factory)
        }

        test("getPreferences maps the DTO to the domain type") {
            runTest {
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } returns
                            AppResult.Success(UserPreferencesDto(1.5f, 30, 10, 20, true))
                    }
                val result = fixture(service).getPreferences()
                result.shouldBeInstanceOf<AppResult.Success<*>>()
                // Assert every field — distinct skip values (30 vs 10) would expose a forward/backward transposition.
                (result as AppResult.Success).data.defaultPlaybackSpeed shouldBe 1.5f
                result.data.defaultSkipForwardSec shouldBe 30
                result.data.defaultSkipBackwardSec shouldBe 10
                result.data.defaultSleepTimerMin shouldBe 20
                result.data.shakeToResetSleepTimer shouldBe true
            }
        }

        // Each setter must send exactly one field on the patch, addressed to the right DTO field.
        listOf<Triple<String, suspend UserPreferencesRepositoryImpl.() -> AppResult<Unit>, UpdateUserPreferencesRequest>>(
            Triple("setDefaultPlaybackSpeed", { setDefaultPlaybackSpeed(2.0f) }, UpdateUserPreferencesRequest(defaultPlaybackSpeed = 2.0f)),
            Triple("setDefaultSkipForwardSec", { setDefaultSkipForwardSec(45) }, UpdateUserPreferencesRequest(defaultSkipForwardSec = 45)),
            Triple("setDefaultSkipBackwardSec", { setDefaultSkipBackwardSec(15) }, UpdateUserPreferencesRequest(defaultSkipBackwardSec = 15)),
            Triple("setDefaultSleepTimerMin", { setDefaultSleepTimerMin(20) }, UpdateUserPreferencesRequest(defaultSleepTimerMin = 20)),
            Triple("setShakeToResetSleepTimer", { setShakeToResetSleepTimer(true) }, UpdateUserPreferencesRequest(shakeToResetSleepTimer = true)),
        ).forEach { (name, call, expectedPatch) ->
            test("$name sends only its field on the patch and returns Unit") {
                runTest {
                    val service =
                        mock<UserPreferencesService> {
                            everySuspend { updateMyPreferences(any()) } returns
                                AppResult.Success(UserPreferencesDto(1.0f, 30, 10, null, false))
                        }
                    fixture(service).call() shouldBe AppResult.Success(Unit)
                    verifySuspend { service.updateMyPreferences(expectedPatch) }
                }
            }
        }

        test("a thrown RPC error becomes AppResult.Failure") {
            runTest {
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { getMyPreferences() } throws RuntimeException("ws down")
                    }
                fixture(service).getPreferences().shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
