package com.calypsan.listenup.client.playback

import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand

/**
 * Trust classification for a controller connecting to the exported [PlaybackService].
 *
 * The service is necessarily `android:exported="true"` (Media3's [androidx.media3.session.MediaLibraryService]
 * requires it for Android Auto / system media controller discovery), so any app on the
 * device can attempt to bind. This enum drives which command set, custom layout, and
 * browse-tree access each controller receives once it connects.
 *
 * Precedence (in [controllerTrustOf]): [OWN_APP] > [AUTO_OR_AUTOMOTIVE] > [MEDIA_NOTIFICATION]
 * > [TRUSTED_SYSTEM] > [UNKNOWN]. Our own notification controller would match both
 * [OWN_APP] and [MEDIA_NOTIFICATION]; [OWN_APP] wins and the outcome is identical.
 */
internal enum class ControllerTrust {
    /** Same UID as the host app — full command set, custom layout, library browse. */
    OWN_APP,

    /** Android Auto companion or Automotive OS controller — full session + library + custom. */
    AUTO_OR_AUTOMOTIVE,

    /** Our own foreground-service media notification — full session + custom layout. */
    MEDIA_NOTIFICATION,

    /** System-signature controller (e.g. SystemUI Bluetooth headset bridge) — default session only. */
    TRUSTED_SYSTEM,

    /** Anything else — default session only, library browse rejected. */
    UNKNOWN,
}

/**
 * Pure classifier — no Android or Media3 types. Lifted out of [classifyController] so
 * the precedence rules can be tested without a Robolectric / `MediaSession` runtime.
 */
internal fun controllerTrustOf(
    isOwnApp: Boolean,
    isAutoOrAutomotive: Boolean,
    isMediaNotification: Boolean,
    isTrusted: Boolean,
): ControllerTrust =
    when {
        isOwnApp -> ControllerTrust.OWN_APP
        isAutoOrAutomotive -> ControllerTrust.AUTO_OR_AUTOMOTIVE
        isMediaNotification -> ControllerTrust.MEDIA_NOTIFICATION
        isTrusted -> ControllerTrust.TRUSTED_SYSTEM
        else -> ControllerTrust.UNKNOWN
    }

/**
 * Media3-aware classifier — combines the runtime probes into [controllerTrustOf].
 */
@OptIn(UnstableApi::class)
internal fun MediaSession.classifyController(controller: MediaSession.ControllerInfo): ControllerTrust =
    controllerTrustOf(
        isOwnApp = controller.uid == Process.myUid(),
        isAutoOrAutomotive = isAutoCompanionController(controller) || isAutomotiveController(controller),
        isMediaNotification = isMediaNotificationController(controller),
        isTrusted = controller.isTrusted,
    )

/**
 * Transport (player) commands granted to a controller at [trust] — play, pause, seek.
 *
 * Extracted so the policy is a pure, testable value rather than an inherited default. Media3 1.11
 * introduced `DEFAULT_UNTRUSTED_PLAYER_COMMANDS` and made `onConnect` hand untrusted controllers
 * read-only access by default, which meant a dependency bump could revoke transport from Android
 * Auto, Wear or the notification with no crash and no compile error — the failure would simply be
 * buttons that do nothing.
 *
 * Every trust level currently grants full transport, exactly as on 1.10.1: [ControllerTrust] gates
 * *custom commands, custom layout and browse*, and has never gated transport. The `when` is
 * exhaustive so adding a trust level forces this decision rather than defaulting into it.
 */
internal fun playerCommandsFor(trust: ControllerTrust): Player.Commands =
    when (trust) {
        ControllerTrust.OWN_APP,
        ControllerTrust.AUTO_OR_AUTOMOTIVE,
        ControllerTrust.MEDIA_NOTIFICATION,
        ControllerTrust.TRUSTED_SYSTEM,
        ControllerTrust.UNKNOWN,
        -> MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    }

/**
 * Builds an [MediaSession.ConnectionResult] tailored to the controller's trust level.
 *
 * - Full-trust controllers ([ControllerTrust.OWN_APP] / [ControllerTrust.AUTO_OR_AUTOMOTIVE]):
 *   default session + library commands, plus all custom audiobook commands, plus the custom layout.
 * - [ControllerTrust.MEDIA_NOTIFICATION]: default session + custom commands + custom layout
 *   (library commands are irrelevant for the notification surface).
 * - [ControllerTrust.TRUSTED_SYSTEM] / [ControllerTrust.UNKNOWN]: default session commands only.
 *   No custom audiobook commands, no custom layout.
 *
 * For [ControllerTrust.UNKNOWN], browse requests are additionally rejected at
 * [androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback.onGetLibraryRoot].
 */
@OptIn(UnstableApi::class)
internal fun MediaSession.buildConnectionResultFor(
    controller: MediaSession.ControllerInfo,
    trust: ControllerTrust,
    customSessionCommands: List<SessionCommand>,
    customLayout: List<CommandButton>,
): MediaSession.ConnectionResult {
    val baseCommands =
        when (trust) {
            ControllerTrust.OWN_APP,
            ControllerTrust.AUTO_OR_AUTOMOTIVE,
            -> MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS

            ControllerTrust.MEDIA_NOTIFICATION,
            ControllerTrust.TRUSTED_SYSTEM,
            ControllerTrust.UNKNOWN,
            -> MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
        }

    val includeCustom =
        trust == ControllerTrust.OWN_APP ||
            trust == ControllerTrust.AUTO_OR_AUTOMOTIVE ||
            trust == ControllerTrust.MEDIA_NOTIFICATION

    val sessionCommands =
        baseCommands
            .buildUpon()
            .apply { if (includeCustom) customSessionCommands.forEach { add(it) } }
            .build()

    val builder =
        MediaSession.ConnectionResult
            // Media3 1.11 deprecated the session-only constructor in favour of this one, which lets
            // the library derive per-controller defaults.
            .AcceptedResultBuilder(this, controller)
            .setAvailableSessionCommands(sessionCommands)
            // Set EXPLICITLY rather than inherited. 1.11 added DEFAULT_UNTRUSTED_PLAYER_COMMANDS and
            // changed onConnect to hand untrusted controllers read-only access by default — so
            // leaving this unset would have let a version bump silently revoke play/pause/seek from
            // whichever surfaces Media3 deems untrusted (Auto, Wear, the notification), with no
            // crash and no compile error. Transport availability is OUR policy decision; ControllerTrust
            // above is where it is expressed, and it has never gated transport.
            //
            // This preserves exactly the permissions every controller had on 1.10.1. Tightening
            // transport for untrusted controllers may well be right, but it is a deliberate product
            // decision, not a side effect of a dependency bump.
            .setAvailablePlayerCommands(playerCommandsFor(trust))

    if (includeCustom) {
        builder.setCustomLayout(customLayout)
    }

    return builder.build()
}
