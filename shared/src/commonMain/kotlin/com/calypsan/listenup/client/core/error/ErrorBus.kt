package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.client.core.toLegacy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Global error bus for surfacing errors from any layer to the UI.
 *
 * Any component (repository, sync engine, background task) emits [AppError]s here and
 * the UI subscribes once in `AppShell` to display them via Snackbar.
 *
 * **Migration status:** this is still a singleton `object` for backward compatibility —
 * 73 call sites across 30 files need to be migrated. The rubric target (Finding 01 D9 +
 * resolved checkpoint) is a DI-provided `single<ErrorBus>` injected into consumers
 * instead of a global. That conversion lands as part of W2b alongside the
 * `Result → AppResult` migration (same files touched).
 *
 * Usage (current shape, will evolve to injected `errorBus.emit(...)`):
 * ```kotlin
 * } catch (e: AppException) {
 *     ErrorBus.emit(e.error)
 * }
 * ```
 */
@Deprecated(
    message = "Legacy ErrorBus consumes the legacy AppError hierarchy. Task 14 migrates this to the unified hierarchy.",
    level = DeprecationLevel.WARNING,
)
object ErrorBus {
    private val _errors = MutableSharedFlow<AppError>(extraBufferCapacity = 16)

    /** Stream of errors emitted from anywhere in the app. */
    val errors: SharedFlow<AppError> = _errors

    /**
     * Emit an error to be displayed in the UI.
     *
     * Non-suspending — safe to call from any context.
     * Drops the error silently if the buffer is full (unlikely with 16 slots).
     */
    fun emit(error: AppError) {
        _errors.tryEmit(error)
    }

    /**
     * Convenience: map a raw [Throwable] to [AppError] and emit it.
     *
     * **Deprecated.** Per Finding 01 D9 / the "One error-mapping site per HTTP client"
     * rubric rule: [ErrorMapper] must run at the Ktor boundary, not at every UI catch
     * site, so consumers should already hold a typed [AppError] (via `AppException.error`)
     * by the time they reach the bus. Keep only until every call site has been migrated
     * in W2b; then delete this overload.
     */
    @Deprecated(
        message =
            "Pass an already-mapped AppError; ErrorMapper runs at the HTTP boundary. " +
                "Callers that catch AppException should emit(appException.error).",
        replaceWith = ReplaceWith("emit(ErrorMapper.map(exception))"),
    )
    fun emit(exception: Throwable) {
        _errors.tryEmit(ErrorMapper.map(exception).toLegacy())
    }
}
