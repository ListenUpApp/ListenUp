package com.calypsan.listenup.client.playback

import android.os.Process
import androidx.annotation.OptIn
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
            .AcceptedResultBuilder(this)
            .setAvailableSessionCommands(sessionCommands)

    if (includeCustom) {
        builder.setCustomLayout(customLayout)
    }

    return builder.build()
}
