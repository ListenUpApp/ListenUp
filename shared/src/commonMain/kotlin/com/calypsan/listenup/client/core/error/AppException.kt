package com.calypsan.listenup.client.core.error

/**
 * Typed marker exception carrying an already-mapped [AppError].
 *
 * Thrown exclusively by the `HttpResponseValidator` boundary (see
 * `installListenUpErrorHandling`) so that downstream code can catch a single exception
 * type and read `exception.error` to get the categorised [AppError] — no re-derivation,
 * no repeated [ErrorMapper] calls, no lossy round-trip through message strings.
 *
 * Catch shape:
 * ```kotlin
 * try { api.fetch() } catch (e: AppException) { return AppResult.Failure(e.error) }
 * ```
 *
 * Source: Ktor `handleResponseExceptionWithRequest` pattern — the validator maps once
 * at the boundary, and everyone downstream consumes the already-typed error.
 */
@Deprecated(
    message = "Legacy throwing-pattern that wraps the legacy AppError. " +
        "Migrate to consuming `AppResult.Failure` directly with the unified " +
        "`com.calypsan.listenup.api.error.AppError`. Deleted in Task 16 of Phase 3.",
    level = DeprecationLevel.WARNING,
)
class AppException(
    val error: AppError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
