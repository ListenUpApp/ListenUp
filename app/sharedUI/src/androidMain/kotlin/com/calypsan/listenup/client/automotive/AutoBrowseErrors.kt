package com.calypsan.listenup.client.automotive

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import com.calypsan.listenup.client.domain.model.AuthState

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
 * Typed browse errors for Android Auto — honest-over-silent in the car (#1239).
 */
internal object AutoBrowseErrors {
    /**
     * Authentication-expired [SessionError] with the error-resolution extras: the head unit
     * shows the label and deep-links into the app (launch intent; the signed-out app lands on
     * sign-in) when the user taps it on the phone.
     */
    fun signedOutError(context: Context): SessionError {
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
                    "Sign in to ListenUp",
                )
                putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, pending)
            }
        return SessionError(
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            "Sign in to ListenUp on your phone.",
            extras,
        )
    }
}
