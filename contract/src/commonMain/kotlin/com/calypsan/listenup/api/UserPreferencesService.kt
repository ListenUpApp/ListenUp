package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.preferences.UserPreferencesDto
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * The caller's own playback preferences. Both methods are scoped to the authenticated principal;
 * there is no cross-user access. RPC-only (no third-party REST resource), mirroring [ProfileService].
 */
@Rpc
interface UserPreferencesService {
    suspend fun getMyPreferences(): AppResult<UserPreferencesDto>

    suspend fun updateMyPreferences(request: UpdateUserPreferencesRequest): AppResult<UserPreferencesDto>
}
