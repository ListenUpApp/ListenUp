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
                (result as AppResult.Success).data.defaultPlaybackSpeed shouldBe 1.5f
                result.data.defaultSleepTimerMin shouldBe 20
                result.data.shakeToResetSleepTimer shouldBe true
            }
        }

        test("setDefaultPlaybackSpeed sends a single-field patch and returns Unit") {
            runTest {
                val service =
                    mock<UserPreferencesService> {
                        everySuspend { updateMyPreferences(any()) } returns
                            AppResult.Success(UserPreferencesDto(2.0f, 30, 10, null, false))
                    }
                fixture(service).setDefaultPlaybackSpeed(2.0f) shouldBe AppResult.Success(Unit)
                verifySuspend {
                    service.updateMyPreferences(UpdateUserPreferencesRequest(defaultPlaybackSpeed = 2.0f))
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
