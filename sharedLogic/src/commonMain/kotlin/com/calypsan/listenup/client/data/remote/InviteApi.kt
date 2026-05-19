package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.appJson
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
 * Contract for invite API operations.
 */
interface InviteApiContract {
    /**
     * Get invite details for the landing/registration screen.
     *
     * @param serverUrl The server URL (e.g., "https://audiobooks.example.com")
     * @param code The invite code
     * Returns [AppResult.Failure] carrying the typed error on network/envelope failure.
     */
    suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): AppResult<InviteDetails>

    /**
     * Claim an invite by creating a new user account.
     *
     * @param serverUrl The server URL
     * @param code The invite code
     * @param password The password for the new account
     * Returns [AppResult.Failure] carrying the typed error on network/envelope failure.
     */
    suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): AppResult<InviteClaimResponse>
}

/**
 * API client for public invite operations (no authentication required).
 *
 * Used for:
 * - Fetching invite details to display on registration screen
 * - Claiming invites to create user accounts
 *
 * Creates a fresh HttpClient per request since server URL is dynamic
 * (comes from the invite deep link, not from stored settings).
 */
class InviteApi : InviteApiContract {
    private fun createClient(serverUrl: String): HttpClient =
        HttpClient {
            installListenUpErrorHandling()

            install(ContentNegotiation) {
                json(appJson)
            }

            defaultRequest {
                url(serverUrl)
                contentType(ContentType.Application.Json)
            }
        }

    override suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): AppResult<InviteDetails> {
        val client = createClient(serverUrl)
        try {
            return apiCall(errorMessage = "Failed to load invite details") {
                client.get("/api/v1/invites/$code").body()
            }
        } finally {
            client.close()
        }
    }

    override suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): AppResult<InviteClaimResponse> {
        val client = createClient(serverUrl)
        try {
            val deviceInfo =
                DeviceInfo(
                    deviceType = "mobile",
                    platform = "Android",
                    platformVersion = "Unknown",
                    clientName = "ListenUp Mobile",
                    clientVersion = "1.0.0",
                    clientBuild = "1",
                    deviceModel =
                        com.calypsan.listenup.core.PlatformUtils
                            .getDeviceModel(),
                )
            return apiCall(errorMessage = "Failed to claim invite") {
                client
                    .post("/api/v1/invites/$code/claim") {
                        setBody(ClaimInviteRequest(password, deviceInfo))
                    }.body()
            }
        } finally {
            client.close()
        }
    }
}

// Request DTOs

@Serializable
private data class ClaimInviteRequest(
    @SerialName("password") val password: String,
    @SerialName("device_info") val deviceInfo: DeviceInfo,
)

/**
 * Device information sent with invite-claim requests, surfaced through the
 * legacy snake_case endpoint shape for session tracking. Will go away when
 * invite migrates to the contract-typed surface.
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

// Response DTOs

/**
 * Invite details returned from the server.
 *
 * Used to display information on the registration screen
 * before the user claims the invite.
 */
@Serializable
data class InviteDetails(
    /** The pre-filled display name for the user */
    @SerialName("name") val name: String,
    /** The pre-filled email address for the user */
    @SerialName("email") val email: String,
    /** The name of the server/library being joined */
    @SerialName("server_name") val serverName: String,
    /** Display name of the person who sent the invite */
    @SerialName("invited_by") val invitedBy: String,
    /** Whether the invite is still valid (not claimed, not expired) */
    @SerialName("valid") val valid: Boolean,
)

/**
 * Response from POST /api/v1/invites/{code}/claim — auth session shape returned
 * by the legacy server. Carries snake_case field names because the invite
 * domain hasn't yet been migrated to the contract-typed surface; the user
 * fields are richer than the contract `User` (firstName/lastName/avatar
 * fields) and a separate migration phase will reconcile the two.
 */
@Serializable
data class InviteClaimResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("user") val user: InviteClaimedUser,
) {
    val userId: String get() = user.id
}

/**
 * User information embedded in [InviteClaimResponse]. Field-rich legacy
 * shape; cf. the contract `User` which is leaner. See
 * `InviteClaimedUser.toDomain()` for the mapping.
 */
@Serializable
data class InviteClaimedUser(
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
