package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.AppError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide error bus for surfacing errors from any layer to the UI.
 *
 * Components emit [AppError]s here; the UI subscribes once in `AppShell` to display them
 * via Snackbar. Bound as a `single<ErrorBus>` in the client DI module — every consumer
 * gets the same instance via constructor injection.
 *
 * Usage:
 * ```kotlin
 * class SomeViewModel(private val errorBus: ErrorBus, ...) {
 *     fun load() = scope.launch {
 *         when (val r = repo.fetch()) {
 *             is AppResult.Failure -> errorBus.emit(r.error)
 *             is AppResult.Success -> ...
 *         }
 *     }
 * }
 * ```
 */
class ErrorBus {
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
}
