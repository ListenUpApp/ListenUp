package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.AppError

/**
 * Typed marker exception carrying an already-mapped [AppError].
 *
 * Throwable courier for data-layer boundaries that propagate failures via `throw`/`catch`
 * rather than returning [com.calypsan.listenup.client.core.AppResult]. Catch sites can
 * read [error] to recover the typed, already-categorised [AppError] without re-deriving
 * it from the message string.
 *
 * Catch shape:
 * ```kotlin
 * try { api.fetch() } catch (e: AppException) { return AppResult.Failure(e.error) }
 * ```
 *
 * Transitional: the codebase has two error-handling conventions side-by-side — most
 * APIs return [com.calypsan.listenup.client.core.AppResult], a minority throw [AppException].
 * Task 27d migrates the throwing APIs to [com.calypsan.listenup.client.core.AppResult],
 * after which this class is deleted. Until then, **prefer returning AppResult in all new
 * data-layer code**; do not throw [AppException] from new APIs.
 */
@Deprecated(
    message =
        "Throwing convention is being unified on AppResult. " +
            "New data-layer code should return `AppResult<T>` instead of throwing. " +
            "Deleted in Task 27d of Phase 3 (data-layer AppResult unification).",
    level = DeprecationLevel.WARNING,
)
class AppException(
    val error: AppError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
