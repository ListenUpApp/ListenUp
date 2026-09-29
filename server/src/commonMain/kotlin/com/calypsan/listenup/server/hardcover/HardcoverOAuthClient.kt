package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.api.SERVER_VERSION
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.ParametersBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The scopes ListenUp asks Hardcover for — your profile id, the public catalog, your own library. */
const val HARDCOVER_SCOPES = "read:me:content read:catalog read:library write:library"

/** Hardcover's production API origin. Tests pass their own. */
const val HARDCOVER_API_BASE_URL = "https://api.hardcover.app"

/**
 * ListenUp's own OAuth client id on Hardcover — a public client (device grant, no secret), registered
 * once for the project, so every self-hosted server can connect Hardcover with no setup. Public by
 * design: a device-flow client id identifies the app, it authorises nothing.
 */
const val HARDCOVER_LISTENUP_CLIENT_ID = "002ce38a-6eb0-4bea-b690-ad51833159cf"

/** Sent on every request — Hardcover asks API callers to identify themselves. */
internal const val HARDCOVER_USER_AGENT = "ListenUp/$SERVER_VERSION"

/**
 * How both Hardcover clients read responses. They decode the body text themselves rather than lean
 * on the injected [HttpClient]'s ContentNegotiation, so an added field on Hardcover's side (or a
 * differently-configured client) never turns a good answer into a failure.
 */
internal val hardcoverJson = Json { ignoreUnknownKeys = true }

/** A granted token pair. [expiresInSeconds] is the ACCESS token's lifetime. */
data class HardcoverTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val scope: String,
)

/** A started device sign-in (RFC 8628 §3.2). */
data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/** Outcome of starting a device sign-in. */
sealed interface DeviceAuthorizationResult {
    /** Started; show [authorization]'s code to the user. */
    data class Started(
        val authorization: DeviceAuthorization,
    ) : DeviceAuthorizationResult

    /** Hardcover couldn't be reached or answered unexpectedly. */
    data class Unavailable(
        val detail: String,
    ) : DeviceAuthorizationResult
}

/** One poll of a pending device sign-in (RFC 8628 §3.5). */
sealed interface TokenPoll {
    /** The user hasn't acted yet — poll again after the interval. */
    data object Pending : TokenPoll

    /** Polling too fast — add 5 seconds to the interval, then poll again. */
    data object SlowDown : TokenPoll

    /** The user approved. */
    data class Granted(
        val tokens: HardcoverTokens,
    ) : TokenPoll

    /** The user declined. */
    data object Denied : TokenPoll

    /** The code expired before the user approved it. */
    data object Expired : TokenPoll

    /** Hardcover couldn't be reached or answered unexpectedly — transient, poll again. */
    data class Unavailable(
        val detail: String,
    ) : TokenPoll
}

/** Outcome of a refresh (RFC 6749 §6). */
sealed interface RefreshResult {
    /** A NEW pair — the old refresh token is now spent and must never be used again. */
    data class Granted(
        val tokens: HardcoverTokens,
    ) : RefreshResult

    /** Hardcover no longer honours the refresh token (revoked, expired, or its chain was replayed). */
    data object InvalidGrant : RefreshResult

    /** Transient — keep the current tokens and try later. */
    data class Unavailable(
        val detail: String,
    ) : RefreshResult
}

/**
 * Hardcover's OAuth endpoints for a PUBLIC client (no secret): the device grant (RFC 8628) to sign
 * in, refresh (RFC 6749 §6, rotating), and revoke (RFC 7009). Form-encoded requests, JSON responses.
 * Every failure is a typed value — a transport error never escapes as an exception, and
 * [CancellationException] is always rethrown.
 */
class HardcoverOAuthClient(
    private val http: HttpClient,
    private val clientId: String,
    private val apiBaseUrl: String = HARDCOVER_API_BASE_URL,
) {
    /** Starts a device sign-in for [HARDCOVER_SCOPES]. */
    suspend fun startDeviceAuthorization(): DeviceAuthorizationResult =
        attempt({ DeviceAuthorizationResult.Unavailable(it) }) {
            val response =
                post("oauth2/device") {
                    append("scope", HARDCOVER_SCOPES)
                }
            if (response.status.isSuccess()) {
                val wire = hardcoverJson.decodeFromString<DeviceAuthorizationWire>(response.bodyAsText())
                DeviceAuthorizationResult.Started(wire.toAuthorization())
            } else {
                DeviceAuthorizationResult.Unavailable("device ${response.status}")
            }
        }

    /** Polls a pending device sign-in once. */
    suspend fun pollToken(deviceCode: String): TokenPoll =
        attempt({ TokenPoll.Unavailable(it) }) {
            val response =
                post("oauth2/token") {
                    append("grant_type", DEVICE_CODE_GRANT)
                    append("device_code", deviceCode)
                }
            if (response.status.isSuccess()) {
                TokenPoll.Granted(tokensFrom(response))
            } else {
                when (errorCode(response)) {
                    "authorization_pending" -> TokenPoll.Pending
                    "slow_down" -> TokenPoll.SlowDown
                    "access_denied" -> TokenPoll.Denied
                    "expired_token" -> TokenPoll.Expired
                    else -> TokenPoll.Unavailable("poll ${response.status}")
                }
            }
        }

    /** Refreshes with [refreshToken]; on success the returned pair REPLACES it. */
    suspend fun refresh(refreshToken: String): RefreshResult =
        attempt({ RefreshResult.Unavailable(it) }) {
            val response =
                post("oauth2/token") {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                }
            when {
                response.status.isSuccess() -> RefreshResult.Granted(tokensFrom(response))
                errorCode(response) == "invalid_grant" -> RefreshResult.InvalidGrant
                else -> RefreshResult.Unavailable("refresh ${response.status}")
            }
        }

    /** Revokes [token] (best effort: returns whether Hardcover acknowledged it). */
    suspend fun revoke(token: String): Boolean =
        attempt({ false }) {
            post("oauth2/revoke") { append("token", token) }.status.isSuccess()
        }

    /** POSTs a form carrying `client_id` plus [fields] to [path], identified by [HARDCOVER_USER_AGENT]. */
    private suspend fun post(
        path: String,
        fields: ParametersBuilder.() -> Unit,
    ): HttpResponse =
        http.submitForm(
            url = "$apiBaseUrl/$path",
            formParameters =
                parameters {
                    append("client_id", clientId)
                    fields()
                },
        ) { header(HttpHeaders.UserAgent, HARDCOVER_USER_AGENT) }

    private suspend fun tokensFrom(response: HttpResponse): HardcoverTokens =
        hardcoverJson.decodeFromString<TokenWire>(response.bodyAsText()).toTokens()

    /** The RFC 6749 §5.2 `error` code of a failed response, or null when the body isn't one. */
    private suspend fun errorCode(response: HttpResponse): String? =
        try {
            hardcoverJson.decodeFromString<OAuthErrorWire>(response.bodyAsText()).error
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Runs [block], turning any non-cancellation failure into [onFailure] — see the class KDoc. */
    private suspend fun <T> attempt(
        onFailure: (String) -> T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e.message ?: e::class.simpleName.orEmpty())
        }

    private companion object {
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

@Serializable
private data class DeviceAuthorizationWire(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("interval") val interval: Long? = null,
)

/** RFC 8628 §3.2: when the server omits `interval`, clients poll every 5 seconds. */
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L

private fun DeviceAuthorizationWire.toAuthorization() =
    DeviceAuthorization(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        verificationUriComplete = verificationUriComplete ?: verificationUri,
        expiresInSeconds = expiresIn,
        intervalSeconds = interval ?: DEFAULT_POLL_INTERVAL_SECONDS,
    )

@Serializable
private data class TokenWire(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("scope") val scope: String = "",
)

private fun TokenWire.toTokens() = HardcoverTokens(accessToken, refreshToken, expiresIn, scope)

@Serializable
private data class OAuthErrorWire(
    @SerialName("error") val error: String? = null,
)
