package com.calypsan.listenup.client.playback

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.localization.SystemStrings
import java.util.Locale

/**
 * The custom buttons the session offers [trust]'s controller beside previous, play/pause and next.
 *
 * Back N and forward N carry the user's configured intervals in both glyph and label: a car is the surface where a
 * button that says one number and moves another is least correctable (#1300). They sit in the secondary slots, beside
 * previous and next, never in them, so at the last chapter next's slot stays empty instead of forward sliding into it.
 * Each falls back to overflow, which is what the platform session (Android Auto, Bluetooth and the Android 13+ system
 * card) reads. That card shows two custom buttons beside the reserved previous and next, so the order decides what it
 * keeps: back N and forward N. Speed comes third, kept by Android Auto (limited to three custom actions by its
 * guidelines), which has room for it and is the car's only way to change speed.
 */
internal fun mediaButtonsFor(
    trust: ControllerTrust,
    backwardSec: Int,
    forwardSec: Int,
    words: SystemStrings,
): List<CommandButton> =
    when (trust) {
        ControllerTrust.OWN_APP,
        ControllerTrust.AUTO_OR_AUTOMOTIVE,
        ControllerTrust.MEDIA_NOTIFICATION,
        -> {
            listOf(
                CommandButton
                    .Builder(SkipCommandIcons.backward(backwardSec))
                    .setDisplayName(words.carBack.format(Locale.getDefault(), backwardSec))
                    .setSessionCommand(SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK, Bundle.EMPTY))
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build(),
                CommandButton
                    .Builder(SkipCommandIcons.forward(forwardSec))
                    .setDisplayName(words.carForward.format(Locale.getDefault(), forwardSec))
                    .setSessionCommand(SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build(),
                CommandButton
                    .Builder(CommandButton.ICON_UNDEFINED)
                    .setDisplayName(words.playerSpeed)
                    .setCustomIconResId(R.drawable.ic_speed)
                    .setSessionCommand(CustomActions.cycleSpeedCommand())
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }

        ControllerTrust.TRUSTED_SYSTEM,
        ControllerTrust.UNKNOWN,
        -> {
            emptyList()
        }
    }

/**
 * What each connected controller should be offered now: its fresh [mediaButtonsFor], for those whose trust gets
 * custom buttons. Connect-time buttons freeze the skip interval and the language, so the service re-sends these
 * whenever either changes.
 */
internal fun mediaButtonUpdates(
    controllers: List<Pair<MediaSession.ControllerInfo, ControllerTrust>>,
    backwardSec: Int,
    forwardSec: Int,
    words: SystemStrings,
): List<Pair<MediaSession.ControllerInfo, List<CommandButton>>> =
    controllers
        .filter { (_, trust) -> trust.getsCustomButtons() }
        .map { (controller, trust) ->
            controller to
                mediaButtonsFor(trust = trust, backwardSec = backwardSec, forwardSec = forwardSec, words = words)
        }
