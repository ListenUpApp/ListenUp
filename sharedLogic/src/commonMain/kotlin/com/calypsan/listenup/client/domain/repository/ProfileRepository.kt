package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.UserProfile

/**
 * Repository contract for user profile operations.
 *
 * [refreshMyProfile] fetches the caller's own profile from the server (via RPC) and
 * writes the result into the local Room cache so [UserRepository.observeCurrentUser]
 * reflects the latest server-side values. It also triggers a local avatar file refresh
 * when the profile reports [avatarType] == `"image"`, and deletes any stale avatar
 * file when [avatarType] == `"auto"`.
 *
 * [getUserProfile] fetches another user's full public profile (stats, recent books,
 * shelves) and is used for the social profile-view screen.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface ProfileRepository {
    /**
     * Fetch the own profile from the server and refresh the local Room cache.
     *
     * - Upserts [UserProfileEntity] so downstream Room observers reflect the latest
     *   displayName / tagline / avatarType from the server.
     * - Updates [UserEntity] tagline and avatarType via [UserDao] so
     *   [UserRepository.observeCurrentUser] emits the server values.
     * - Queues a force-refresh avatar download when [avatarType] == `"image"`;
     *   deletes the local avatar file when [avatarType] == `"auto"`.
     *
     * Never-Stranded: failures are surfaced as [AppResult.Failure] and never thrown.
     * The UI can display the local cache while the refresh is in-flight.
     *
     * @return [AppResult.Success] on a successful server round-trip, [AppResult.Failure] otherwise.
     */
    suspend fun refreshMyProfile(): AppResult<Unit>

    /**
     * Get a user's public profile.
     *
     * @param userId The user ID to fetch profile for
     * @return Result containing the user profile or an error
     */
    suspend fun getUserProfile(userId: String): AppResult<UserProfile>
}
