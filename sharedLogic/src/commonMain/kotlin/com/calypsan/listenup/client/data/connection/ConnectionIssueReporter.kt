package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.data.auth.invalidatesSession
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Edge-triggered fold for headless-seam connection reports (SSE auth exhaustion, digest and
 * catch-up failures). Session-invalidating [AppError]s are forwarded to the [ErrorBus] exactly
 * once per lapse — a 19-domain reconcile burst produces one bus emission, one snackbar, one
 * [com.calypsan.listenup.client.data.auth.AuthFailureObserver] action. Everything else is
 * swallowed (the reporting seams keep their own `logger.warn`).
 *
 * Phase-1 slice of the Connection Resilience design's `ConnectionHealthStore.report()` fold
 * (spec §5.2); the Phase-2 store absorbs this class.
 */
internal class ConnectionIssueReporter(
    private val errorBus: ErrorBus,
    private val authSession: AuthSession,
    scope: CoroutineScope,
) {
    // Best-effort dedup flag (same deliberately-unsynchronized discipline as SyncSseClient's
    // backoff counters): the worst race is one duplicate bus emission, which the observer's
    // Authenticated-only guard already tolerates.
    private var reportedSinceAuthenticated = false

    init {
        scope.launch {
            // Plain flag write — cannot throw, so no per-item guard is needed here.
            authSession.authState.collect { state ->
                if (state is AuthState.Authenticated) reportedSinceAuthenticated = false
            }
        }
    }

    /**
     * Report a typed failure observed by a headless seam. Cheap and non-suspending — safe to
     * call redundantly from every failure branch.
     */
    fun report(error: AppError) {
        if (!error.invalidatesSession()) return
        if (reportedSinceAuthenticated) return
        if (authSession.authState.value !is AuthState.Authenticated) return
        reportedSinceAuthenticated = true
        errorBus.emit(error)
    }
}
