package com.calypsan.listenup.api.dto.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The caller's playback preferences, as returned by [com.calypsan.listenup.api.UserPreferencesService].
 * Named `…Dto` to avoid colliding with the client-side domain `UserPreferences`; the client repository
 * maps this to that domain type.
 */
@Serializable
@SerialName("UserPreferencesDto")
data class UserPreferencesDto(
    val defaultPlaybackSpeed: Float,
    val defaultSkipForwardSec: Int,
    val defaultSkipBackwardSec: Int,
    val defaultSleepTimerMin: Int? = null,
    val shakeToResetSleepTimer: Boolean,
)

/**
 * Partial update for playback preferences — every field is nullable; only non-null fields are merged
 * onto the caller's stored preferences (PATCH semantics, mirroring the Go reference).
 */
@Serializable
@SerialName("UpdateUserPreferencesRequest")
data class UpdateUserPreferencesRequest(
    val defaultPlaybackSpeed: Float? = null,
    val defaultSkipForwardSec: Int? = null,
    val defaultSkipBackwardSec: Int? = null,
    val defaultSleepTimerMin: Int? = null,
    val shakeToResetSleepTimer: Boolean? = null,
)
