package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.domain.model.User

/**
 * Domain-level result from a successful login.
 *
 * Contains all data needed to establish a session:
 * - Auth tokens for API calls
 * - Session ID for logout
 * - User ID for identification
 * - User data for local storage
 */
data class LoginResult(
    val accessToken: AccessToken,
    val refreshToken: RefreshToken,
    val sessionId: String,
    val userId: String,
    val user: User,
)

/**
 * Domain-level result from registration.
 *
 * Registration creates a pending user that requires admin approval.
 */
data class RegistrationResult(
    val userId: String,
    val message: String,
)

/**
 * Repository contract for authentication operations.
 *
 * Abstracts the authentication API behind a domain interface, allowing
 * use cases to remain pure and independent of network implementation.
 *
 * Implementations handle:
 * - Network calls to auth endpoints
 * - Mapping API responses to domain types
 * - Error transformation
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface AuthRepository {
    /**
     * Authenticate with email and password.
     *
     * On success, returns tokens and user data for session establishment.
     * The use case is responsible for storing tokens via AuthSession.
     *
     * @param email User's email address
     * @param password User's password
     * @return LoginResult containing tokens and user data
     * @throws Exception on network errors or invalid credentials
     */
    suspend fun login(
        email: String,
        password: String,
    ): LoginResult

    /**
     * Register a new user account.
     *
     * Creates a pending user that requires admin approval.
     * The user cannot log in until approved.
     *
     * @param email User's email address
     * @param password User's password (min 8 characters)
     * @param firstName User's first name
     * @param lastName User's last name
     * @return RegistrationResult with user ID and message
     * @throws Exception on network errors, validation failures, or if registration is disabled
     */
    suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): RegistrationResult

    /**
     * Logout and invalidate server session.
     *
     * Best-effort operation - callers should handle failures gracefully
     * and proceed with local cleanup regardless.
     *
     * @param sessionId The session to invalidate
     * @throws Exception on network errors (should be caught by caller)
     */
    suspend fun logout(sessionId: String)

    /**
     * Create the root/admin user during initial server setup.
     *
     * This is only available when the server has no users configured.
     * Once a root user exists, this will return an error.
     *
     * @param email User's email address
     * @param password User's password (min 8 characters)
     * @param firstName User's first name
     * @param lastName User's last name
     * @return LoginResult with tokens and user info on success
     * @throws Exception on network errors or if setup is already complete
     */
    suspend fun setup(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): LoginResult

    /**
     * Check the approval status of a pending registration.
     *
     * Used to poll for approval after registering.
     *
     * @param userId User ID from registration response
     * @return RegistrationStatus with current status
     */
    suspend fun checkRegistrationStatus(userId: String): RegistrationStatus
}

/**
 * Status of a pending registration.
 */
data class RegistrationStatus(
    val userId: String,
    val status: String,
    val approved: Boolean,
)
