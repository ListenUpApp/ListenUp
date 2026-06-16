package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The user-preferences DTOs cross the RPC wire; round-trip them through the contract JSON to
 * guard against serialization drift (field names, nullability, defaults).
 */
class UserPreferencesDtoContractTest :
    FunSpec({
        val json = contractJson

        test("UserPreferencesDto round-trips") {
            val dto =
                UserPreferencesDto(
                    defaultPlaybackSpeed = 1.5f,
                    defaultSkipForwardSec = 30,
                    defaultSkipBackwardSec = 10,
                    defaultSleepTimerMin = 20,
                    shakeToResetSleepTimer = true,
                )
            json.decodeFromString(UserPreferencesDto.serializer(), json.encodeToString(UserPreferencesDto.serializer(), dto)) shouldBe dto
        }

        test("UpdateUserPreferencesRequest round-trips with nulls (partial patch)") {
            val patch = UpdateUserPreferencesRequest(defaultPlaybackSpeed = 2.0f)
            val decoded =
                json.decodeFromString(
                    UpdateUserPreferencesRequest.serializer(),
                    json.encodeToString(UpdateUserPreferencesRequest.serializer(), patch),
                )
            decoded shouldBe patch
            decoded.defaultSkipForwardSec shouldBe null
        }
    })
