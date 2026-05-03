package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.PlatformUtils
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.core.error.AppException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API client for authentication operations.
 *
 * Uses its own HttpClient instance (no auth plugin) since these endpoints
 * don't require authentication headers. The auth plugin would interfere
 * with token refresh logic.
 *
 * Reads server URL dynamically from SettingsRepository to support runtime
 * URL changes (e.g., user connecting to a different server).
 *
 * All endpoints return new tokens on success, following the server's
 * token rotation pattern (15min access, 30d refresh).
 */
class AuthApi(
    private val getServerUrl: suspend () -> ServerUrl?,
) : AuthApiContract {
    private fun createClient(serverUrl: ServerUrl): HttpClient =
        HttpClient {
            installListenUpErrorHandling()

            install(ContentNegotiation) {
                json(appJson)
            }

            defaultRequest {
                url(serverUrl.value)
                contentType(ContentType.Application.Json)
            }
        }

    /**
     * Get server URL or throw if not configured.
     */
    private suspend fun requireServerUrl(): ServerUrl = getServerUrl() ?: error("Server URL not configured")

    /**
     * Create the root/admin user during initial server setup.
     *
     * This endpoint is only available when the server has no users configured.
     * Once a root user exists, this endpoint will return an error.
     *
     * @param email User's email address (will be username)
     * @param password User's password (min 8 characters)
     * @param firstName User's first name
     * @param lastName User's last name
     * @return AuthResponse with tokens and user info on success
     * @throws Exception on network errors or validation failures
     */
    override suspend fun setup(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AuthResponse {
        val client = createClient(requireServerUrl())
        try {
            val response: ApiResponse<AuthResponse> =
                client
                    .post("/api/v1/auth/setup") {
                        setBody(SetupRequest(email, password, firstName, lastName))
                    }.body()

            return when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Login with email and password.
     *
     * @return AuthResponse with tokens and user info on success
     * @throws Exception on network errors or invalid credentials
     */
    override suspend fun login(
        email: String,
        password: String,
    ): AuthResponse {
        val deviceInfo =
            DeviceInfo(
                deviceType = "mobile",
                platform = getPlatform(),
                platformVersion = getPlatformVersion(),
                clientName = "ListenUp Mobile",
                clientVersion = "1.0.0",
                clientBuild = "1",
                deviceModel = getDeviceModel(),
            )

        val client = createClient(requireServerUrl())
        try {
            val response: ApiResponse<AuthResponse> =
                client
                    .post("/api/v1/auth/login") {
                        setBody(LoginRequest(email, password, deviceInfo))
                    }.body()

            return when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Get the current platform name via expect/actual pattern.
     */
    private fun getPlatform(): String = PlatformUtils.getPlatformName()

    /**
     * Get the current platform version via expect/actual pattern.
     */
    private fun getPlatformVersion(): String = PlatformUtils.getPlatformVersion()

    /**
     * Get the device model via expect/actual pattern.
     */
    private fun getDeviceModel(): String = PlatformUtils.getDeviceModel()

    /**
     * Refresh access token using refresh token.
     *
     * Server rotates both tokens on refresh for security.
     * Old tokens are invalidated after successful refresh.
     *
     * @return AuthResponse with new token pair
     * @throws Exception on network errors or invalid/expired refresh token
     */
    override suspend fun refresh(refreshToken: RefreshToken): AuthResponse {
        val client = createClient(requireServerUrl())
        try {
            val response: ApiResponse<AuthResponse> =
                client
                    .post("/api/v1/auth/refresh") {
                        setBody(RefreshRequest(refreshToken.value))
                    }.body()

            return when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Logout and invalidate current session.
     *
     * Server invalidates both access and refresh tokens.
     * Always succeeds even if tokens are already invalid.
     */
    override suspend fun logout(sessionId: String) {
        val client = createClient(requireServerUrl())
        try {
            client.post("/api/v1/auth/logout") {
                setBody(LogoutRequest(sessionId))
            }
        } finally {
            client.close()
        }
    }

    /**
     * Register a new user account when open registration is enabled.
     *
     * Creates a user with pending status that requires admin approval.
     * The user cannot log in until an admin approves their account.
     *
     * @param email User's email address (will be username)
     * @param password User's password (min 8 characters)
     * @param firstName User's first name
     * @param lastName User's last name
     * @return RegisterResponse with user ID and success message
     * @throws Exception on network errors, validation failures, or if registration is disabled
     */
    override suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): RegisterResponse {
        val client = createClient(requireServerUrl())
        try {
            val response: ApiResponse<RegisterResponse> =
                client
                    .post("/api/v1/auth/register") {
                        setBody(RegisterRequest(email, password, firstName, lastName))
                    }.body()

            return when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Check the approval status of a pending registration.
     *
     * Used to poll for approval after registering. Once approved,
     * the client can proceed with login.
     *
     * @param userId User ID from registration response
     * @return RegistrationStatusResponse with current status
     */
    override suspend fun checkRegistrationStatus(userId: String): RegistrationStatusResponse {
        val client = createClient(requireServerUrl())
        try {
            val response: ApiResponse<RegistrationStatusResponse> =
                client
                    .get("/api/v1/auth/registration-status/$userId")
                    .body()

            return when (val result = response.toResult()) {
                is Success -> result.data
                is Failure -> throw AppException(result.error)
            }
        } finally {
            client.close()
        }
    }
}

// Request DTOs

@Serializable
private data class SetupRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
)

@Serializable
private data class RegisterRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
)

@Serializable
private data class LoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("device_info") val deviceInfo: DeviceInfo,
)

/**
 * Device information sent with authentication requests.
 * Used for session tracking and device-specific features.
 */
@Serializable
internal data class DeviceInfo(
    @SerialName("device_type") val deviceType: String,
    @SerialName("platform") val platform: String,
    @SerialName("platform_version") val platformVersion: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("client_build") val clientBuild: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("browser_name") val browserName: String = "",
    @SerialName("browser_version") val browserVersion: String = "",
)

@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
)

@Serializable
private data class LogoutRequest(
    @SerialName("session_id") val sessionId: String,
)

// Response DTOs

/**
 * Authentication response from server.
 * Contains new tokens and user/session information.
 */
@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("user") val user: AuthUser,
) {
    // Convenience accessors for common user fields
    val userId: String get() = user.id
    val email: String get() = user.email
    val displayName: String get() = user.displayName
    val isRoot: Boolean get() = user.isRoot
}

/**
 * User information included in authentication responses.
 */
@Serializable
data class AuthUser(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("is_root") val isRoot: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_login_at") val lastLoginAt: String,
    @SerialName("avatar_type") val avatarType: String = "auto",
    @SerialName("avatar_value") val avatarValue: String? = null,
    @SerialName("avatar_color") val avatarColor: String = "#6B7280",
)

/**
 * Response from registration endpoint.
 *
 * Contains the user ID and a success message indicating the account was created
 * with pending status and requires admin approval.
 */
@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("message") val message: String,
)

/**
 * Response from registration status check endpoint.
 *
 * Used to poll for approval status after registration.
 */
@Serializable
data class RegistrationStatusResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("status") val status: String,
    @SerialName("approved") val approved: Boolean,
)
