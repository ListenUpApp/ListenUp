package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root sealed interface for every error in the app.
 *
 * Single source of truth: wire-borne errors and purely client-local errors
 * both extend this hierarchy. The wire transport (kotlinx.rpc + REST) carries
 * any subtype as a `@Serializable` polymorphic value; client-local errors
 * carry [correlationId] = `null`.
 *
 * Presentation fields ([message], [code], [isRetryable], [debugInfo]) live
 * on the type, not in a parallel UI hierarchy. UI consumes [AppError] directly.
 *
 * Konsist rule `noLegacyAppErrorReferences` forbids any new reference to the
 * deleted `client.core.error.AppError` hierarchy.
 */
@Serializable
sealed interface AppError {
    /** Server-issued correlation id matching the request's MDC field and `X-Request-Id`. Null on purely client-local errors. */
    val correlationId: String?

    /** User-facing message — non-technical, default-renderable in UI. */
    val message: String

    /** Stable error code for logs/analytics, e.g. `"AUDIO_META_CORRUPT_HEADER"`. */
    val code: String

    /** Whether retrying the originating action is reasonable. */
    val isRetryable: Boolean

    /** Technical debug detail — populated in debug builds, null in release. */
    val debugInfo: String?
}

/**
 * Catch-all for unmapped server-side exceptions. The RPC exception
 * interceptor returns this when an internal error is not a typed
 * domain error. The stacktrace stays on the server; the client only
 * sees the correlation id.
 */
@Serializable
@SerialName("AppError.InternalError")
data class InternalError(
    override val correlationId: String? = null,
    override val debugInfo: String? = null,
    /** Simple class name of the original throwable, e.g. `"NullPointerException"`. Null on client-local errors. */
    val cause: String? = null,
) : AppError {
    override val message: String = "Something went wrong on the server."
    override val code: String = "INTERNAL_ERROR"
    override val isRetryable: Boolean = false
}

/**
 * Input failed validation — surfaces both client-side pre-flight checks
 * and server-side `init`-block violations on `@Serializable` requests.
 *
 * Distinct from typed domain errors like [AuthError.InvalidCredentials]:
 * `ValidationError` means "your input is malformed," not "the server
 * rejected your credentials."
 */
@Serializable
@SerialName("AppError.ValidationError")
data class ValidationError(
    override val message: String,
    override val correlationId: String? = null,
    override val debugInfo: String? = null,
    /**
     * Names the input field this error refers to (e.g. `"email"`, `"password"`) so a form can
     * highlight the right input without substring-matching the per-instance [message] — a match
     * that silently breaks the moment the message is reworded. Null when the error is not tied to
     * a single named field. New, nullable, and defaulted so it stays wire-backward-compatible.
     */
    val field: String? = null,
) : AppError {
    override val code: String = "VALIDATION_ERROR"
    override val isRetryable: Boolean = false
}
