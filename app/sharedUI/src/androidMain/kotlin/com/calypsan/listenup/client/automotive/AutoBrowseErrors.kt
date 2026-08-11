package com.calypsan.listenup.client.automotive

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.localization.SystemStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Whether the Auto browse surface should answer with the typed signed-out error instead of
 * content (#1239). Only genuinely signed-out states gate: transient startup states
 * ([AuthState.Initializing], [AuthState.CheckingServer]) must NOT flash a sign-in prompt —
 * browse reads Room and works offline while a stored session is being restored.
 */
internal fun browseNeedsSignIn(state: AuthState): Boolean =
    when (state) {
        is AuthState.NeedsServerUrl,
        is AuthState.NeedsSetup,
        is AuthState.NeedsLogin,
        is AuthState.PendingApproval,
        -> true

        // SessionLapsed deliberately does NOT gate: credentials are dead but local data is
        // intact (never stranded) — browse serves the Room mirror offline, exactly like the
        // in-app shell, which shows a non-blocking "sign in to sync" affordance instead of a wall.
        is AuthState.Initializing,
        is AuthState.CheckingServer,
        is AuthState.Authenticated,
        is AuthState.SessionLapsed,
        -> false
    }

/**
 * Emits once for every signed-out → signed-in edge in [this] auth state stream (#1245).
 *
 * A head unit that was told to sign in has cached that error and has no reason of its own to ask
 * again — the listener signs in on the phone and the car keeps showing the wall until they back
 * out of the app and re-enter. This is the signal that closes that gap; the caller answers it with
 * `notifyChildrenChanged` so the browse tree re-queries in place.
 *
 * Derived from [browseNeedsSignIn] rather than matching [AuthState.Authenticated] directly, so the
 * edge can never disagree with the gate that produced the error — notably `SessionLapsed`, which
 * does not gate browse and so is not an edge worth refreshing for.
 *
 * The current state is dropped before filtering: a service that starts up already signed in has
 * nothing to refresh, and only a genuine transition is an edge.
 */
internal fun Flow<AuthState>.browseSignInEdges(): Flow<Unit> =
    map { browseNeedsSignIn(it) }
        .distinctUntilChanged()
        .drop(1)
        .filter { needsSignIn -> !needsSignIn }
        .map { }

/**
 * Typed browse errors for Android Auto — honest-over-silent in the car (#1239).
 */
internal object AutoBrowseErrors {
    /**
     * Authentication-expired [SessionError] with the error-resolution extras: the head unit
     * shows the label and deep-links into the app (launch intent; the signed-out app lands on
     * sign-in) when the user taps it on the phone.
     */
    fun signedOutError(
        context: Context,
        strings: SystemStrings,
    ): SessionError {
        val launch =
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
                "own package has no launch intent"
            }
        val pending =
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val extras =
            Bundle().apply {
                putString(
                    MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                    strings.carSignInAction,
                )
                putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, pending)
            }
        return SessionError(
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            strings.carSignInMessage,
            extras,
        )
    }
}
