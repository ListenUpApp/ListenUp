package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * The caller's own profile. Both methods are scoped to the authenticated principal; there is no
 * cross-user read here. The avatar binary (upload + serve) lives on REST routes, not this service.
 */
@Rpc
interface ProfileService {
    suspend fun getMyProfile(): AppResult<Profile>

    suspend fun updateMyProfile(request: UpdateProfileRequest): AppResult<Profile>
}
