package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for user profile operations.
 *
 * Defines the interface for accessing the current user's data.
 * Implementations handle data source details (Room, API, etc).
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface UserRepository {
    /**
     * Observe the current user reactively.
     *
     * Emits whenever user data changes, enabling real-time UI updates
     * for profile information, display name, and avatar changes.
     *
     * @return Flow that emits User when available, null if not logged in
     */
    fun observeCurrentUser(): Flow<User?>

    /**
     * Observe whether the current user has admin privileges.
     *
     * Used by ViewModels to conditionally show admin-only features
     * such as user management, library configuration, and invites.
     *
     * @return Flow emitting true if user is admin, false otherwise
     */
    fun observeIsAdmin(): Flow<Boolean>

    /**
     * Get the current user synchronously.
     *
     * Useful for one-time checks where reactive observation isn't needed.
     *
     * @return User if logged in, null otherwise
     */
    suspend fun getCurrentUser(): User?

    /**
     * Save or update user data.
     *
     * Used after login to persist the user's profile locally.
     * If user already exists, updates their data.
     *
     * @param user The user to save
     */
    suspend fun saveUser(user: User)

    /**
     * Clear all user data.
     *
     * Used during logout to remove local user profile.
     */
    suspend fun clearUsers()

    /**
     * Refresh current user data from the server.
     *
     * Fetches the user's profile from the server and saves it locally.
     * Used when local user data is missing but authentication tokens exist.
     *
     * @return The refreshed user on success, null if fetch failed or not authenticated
     */
    suspend fun refreshCurrentUser(): User?
}
