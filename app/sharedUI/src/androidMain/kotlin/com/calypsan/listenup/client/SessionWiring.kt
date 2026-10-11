package com.calypsan.listenup.client

import com.calypsan.listenup.client.domain.model.AuthState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

private val logger = KotlinLogging.logger {}

/** What an [AuthState] says about whether a live session exists. */
private enum class SessionPhase { SignedIn, SignedOut, Undecided }

/**
 * Written as an exhaustive `when` with no `else`: a new [AuthState] variant must fail this build
 * and be classified, as [com.calypsan.listenup.client.domain.model.isInShell] does.
 *
 * [AuthState.SessionLapsed] is [SessionPhase.Undecided]: the shell stays up with local data intact,
 * so the listener has not signed out. [AuthState.Initializing] and [AuthState.CheckingServer] are
 * startup states that have not decided yet — never "signed out".
 */
private fun AuthState.phase(): SessionPhase =
    when (this) {
        is AuthState.Authenticated -> SessionPhase.SignedIn

        is AuthState.NeedsServerUrl,
        is AuthState.NeedsSetup,
        is AuthState.NeedsLogin,
        is AuthState.PendingApproval,
        -> SessionPhase.SignedOut

        is AuthState.Initializing,
        is AuthState.CheckingServer,
        is AuthState.SessionLapsed,
        -> SessionPhase.Undecided
    }

/**
 * Runs wiring that depends on a live session: [onSignedIn] on every sign-in, [onSignedOut] on every
 * sign-out, for the life of the process.
 *
 * A lapsed session that is signed in again counts as a new sign-in (its credentials, and so its
 * push registration, are new); a lapse on its own is not a sign-out. [onSignedOut] also fires when
 * the process has not signed in yet, so work an earlier process left scheduled is still undone.
 * A callback that throws is logged and collection continues.
 */
internal class SessionWiring(
    private val authStates: Flow<AuthState>,
    private val onSignedIn: suspend () -> Unit,
    private val onSignedOut: suspend () -> Unit,
) {
    /** Collects [authStates] until the caller's scope is cancelled. */
    suspend fun run() {
        var wiredFor: AuthState.Authenticated? = null
        var signedOutActedOn = false
        authStates.collect { state ->
            when (state.phase()) {
                SessionPhase.SignedIn -> {
                    signedOutActedOn = false
                    if (state != wiredFor) {
                        wiredFor = state as AuthState.Authenticated
                        guarded("sign-in", onSignedIn)
                    }
                }

                SessionPhase.SignedOut -> {
                    wiredFor = null
                    if (!signedOutActedOn) {
                        signedOutActedOn = true
                        guarded("sign-out", onSignedOut)
                    }
                }

                // A lapsed session keeps its shell, but its next sign-in has new credentials.
                SessionPhase.Undecided -> {
                    if (state is AuthState.SessionLapsed) wiredFor = null
                }
            }
        }
    }

    private suspend fun guarded(
        what: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Session $what wiring failed" }
        }
    }
}
