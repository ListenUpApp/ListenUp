package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds

/**
 * What an end-to-end auth self-check observed. Plain values, same reasoning as [ClientGraphProbe]:
 * the Koin graph, the Room layer and the RPC proxies all stay `internal` to this module.
 */
data class AuthArcProbe(
    /** True once the real [SetupViewModel] reported success creating the first admin. */
    val setupSucceeded: Boolean,
    /** True once `AuthSession.authState` reached [AuthState.Authenticated]. */
    val reachedAuthenticated: Boolean,
    /** True once an authenticated RPC call returned [AppResult.Success] over the wire. */
    val authedCallSucceeded: Boolean,
    /** `AppError.code` from a failed authed call, or null when the call succeeded. */
    val authedCallErrorCode: String?,
    /**
     * Users the authed call returned, or -1 if it failed.
     *
     * Counted rather than merely succeeded-on: after setup the probe's own admin is the only
     * account, so exactly one user is proof the call reached the server. An empty list would be
     * indistinguishable from a local read that never left the browser.
     */
    val userCount: Int,
)

/**
 * Drives the **whole** auth arc in a browser against a real server: boots the shared graph, creates
 * the first admin through the real [SetupViewModel], waits for `authState` to reach
 * [AuthState.Authenticated], then makes an authenticated RPC call and reports whether it succeeded.
 *
 * The authed call is the point. Reaching [AuthState.Authenticated] proves only that a state machine
 * ran; it says nothing about whether the access token reached the RPC channel's bearer provider. A
 * check that stops at the state machine is structurally blind to a broken token handoff — the
 * single most likely thing to be wrong the first time a browser holds a session.
 *
 * **Runs against a server with no users.** The harness (`with-server.mjs`) hands each run a fresh
 * `mkdtemp` home, so the server boots empty and [AuthState.NeedsSetup] is genuinely reachable — no
 * fixture user needed. That in turn makes this a **once-per-server-boot** operation: a second
 * caller would find the server already configured and fail for reasons that have nothing to do
 * with what this probe tests.
 *
 * Waits rather than hangs: a state that never arrives reports `false`, so a failure reads as a
 * failed assertion instead of a truncated suite.
 *
 * [dbName] overrides the production database name so OPFS state from other runs cannot leak in;
 * OPFS outlives the page and the harness reuses the browser profile.
 */
suspend fun probeAuthArc(
    worker: Worker,
    dbName: String,
    email: String,
    password: String,
): AuthArcProbe {
    val app = browserGraph(worker, dbName)

    return try {
        val authSession = app.koin.get<AuthSession>()
        authSession.initializeAuthState()

        val setup = app.koin.get<SetupViewModel>()
        setup.onSetupSubmit(
            firstName = PROBE_FIRST_NAME,
            lastName = PROBE_LAST_NAME,
            email = email,
            password = password,
            passwordConfirm = password,
        )
        val setupSucceeded =
            withTimeoutOrNull(ARC_TIMEOUT) {
                setup.state.filterIsInstance<SetupUiState.Success>().first()
            } != null

        val reachedAuthenticated =
            withTimeoutOrNull(ARC_TIMEOUT) {
                authSession.authState.filterIsInstance<AuthState.Authenticated>().first()
            } != null

        // The call that actually matters. Admin-only, so it cannot succeed without a bearer token
        // having reached the RPC channel — precisely the handoff the state machine above cannot see.
        val authed = app.koin.get<AdminRepository>().getUsers()

        AuthArcProbe(
            setupSucceeded = setupSucceeded,
            reachedAuthenticated = reachedAuthenticated,
            authedCallSucceeded = authed is AppResult.Success,
            authedCallErrorCode = (authed as? AppResult.Failure)?.error?.code,
            userCount = (authed as? AppResult.Success)?.data?.size ?: NO_USER_COUNT,
        )
    } finally {
        app.close()
    }
}

private val ARC_TIMEOUT = 20.seconds

private const val NO_USER_COUNT = -1
private const val PROBE_FIRST_NAME = "Probe"
private const val PROBE_LAST_NAME = "Admin"
