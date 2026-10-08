package com.calypsan.listenup.server.hardcover

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Hardcover request's outcome, classified the way the push worker's error policy reads it
 * (spec B2): back off on [Throttled], retry with a cap on [Failed], refresh once on [Unauthorized],
 * and break the connection on [MissingScope]. Only [Ok] carries a value, so every other case is a
 * `HardcoverCall<Nothing>` and passes through [map] and [valueOr] untouched.
 */
sealed interface HardcoverCall<out T> {
    /** Hardcover answered with data. */
    data class Ok<out T>(
        val value: T,
    ) : HardcoverCall<T>

    /** 401: Hardcover rejected the access token. */
    data object Unauthorized : HardcoverCall<Nothing>

    /** 403 `insufficient_scope`: the connection was granted without [scope] (null when unnamed). */
    data class MissingScope(
        val scope: String?,
    ) : HardcoverCall<Nothing>

    /** 429 or 503: slow down. [retryAfterMs] is Hardcover's own `Retry-After`, when it sent one. */
    data class Throttled(
        val retryAfterMs: Long?,
    ) : HardcoverCall<Nothing>

    /** Anything else: 408, another 4xx or 5xx, a GraphQL or mutation error, or no answer at all. */
    data class Failed(
        val detail: String,
    ) : HardcoverCall<Nothing>
}

/** Transforms an [HardcoverCall.Ok] value; every failure passes through as it is. */
inline fun <T, R> HardcoverCall<T>.map(transform: (T) -> R): HardcoverCall<R> =
    when (this) {
        is HardcoverCall.Ok -> HardcoverCall.Ok(transform(value))
        HardcoverCall.Unauthorized -> HardcoverCall.Unauthorized
        is HardcoverCall.MissingScope -> this
        is HardcoverCall.Throttled -> this
        is HardcoverCall.Failed -> this
    }

/**
 * The [HardcoverCall.Ok] value, or [onFailure] with the failure. [onFailure] must not complete
 * normally — the intended use is a non-local `return` from the caller: `call.valueOr { return it }`.
 */
inline fun <T> HardcoverCall<T>.valueOr(onFailure: (HardcoverCall<Nothing>) -> Nothing): T =
    when (this) {
        is HardcoverCall.Ok -> value
        HardcoverCall.Unauthorized -> onFailure(HardcoverCall.Unauthorized)
        is HardcoverCall.MissingScope -> onFailure(this)
        is HardcoverCall.Throttled -> onFailure(this)
        is HardcoverCall.Failed -> onFailure(this)
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val INSUFFICIENT_SCOPE = "insufficient_scope"
private val SCOPE_IN_CHALLENGE = Regex("""scope="([^"]*)"""")

/** The `scope="…"` a `WWW-Authenticate` challenge names, if it names one. */
private fun scopeInChallenge(challenge: String): String? = SCOPE_IN_CHALLENGE.find(challenge)?.run { groupValues[1] }

/**
 * Classifies one Hardcover HTTP answer. A 2xx whose body carries a GraphQL `errors` array is a
 * failure too: Hasura reports a malformed or forbidden query that way, with status 200.
 */
internal fun classifyHardcoverResponse(
    status: HttpStatusCode,
    retryAfter: String?,
    wwwAuthenticate: String?,
    body: String,
    label: String,
): HardcoverCall<String> {
    val oauthError = oauthErrorIn(body)
    return when {
        status.isSuccess() -> {
            graphQlErrorIn(body)?.let { HardcoverCall.Failed("$label: $it") } ?: HardcoverCall.Ok(body)
        }

        status == HttpStatusCode.Unauthorized -> {
            HardcoverCall.Unauthorized
        }

        status == HttpStatusCode.Forbidden &&
            (oauthError?.error == INSUFFICIENT_SCOPE || wwwAuthenticate?.contains(INSUFFICIENT_SCOPE) == true) -> {
            HardcoverCall.MissingScope(
                oauthError?.scope ?: wwwAuthenticate?.let(::scopeInChallenge),
            )
        }

        status == HttpStatusCode.TooManyRequests || status == HttpStatusCode.ServiceUnavailable -> {
            HardcoverCall.Throttled(retryAfter?.trim()?.toLongOrNull()?.times(MILLIS_PER_SECOND))
        }

        else -> {
            HardcoverCall.Failed("$label ${status.value}${oauthError?.error?.let { " $it" }.orEmpty()}")
        }
    }
}

private fun graphQlErrorIn(body: String): String? =
    decodeOrNull<GraphQlErrorBody>(body)
        ?.run { errors.firstOrNull() }
        ?.run { message.ifBlank { "GraphQL error" } }

private fun oauthErrorIn(body: String): OAuthErrorBody? = decodeOrNull<OAuthErrorBody>(body)

private inline fun <reified T> decodeOrNull(body: String): T? =
    try {
        hardcoverJson.decodeFromString<T>(body)
    } catch (_: IllegalArgumentException) {
        null
    }

@Serializable
private data class GraphQlErrorBody(
    @SerialName("errors") val errors: List<GraphQlErrorWire> = emptyList(),
)

@Serializable
private data class GraphQlErrorWire(
    @SerialName("message") val message: String = "",
)

@Serializable
private data class OAuthErrorBody(
    @SerialName("error") val error: String? = null,
    @SerialName("scope") val scope: String? = null,
)
