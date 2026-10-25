package com.calypsan.listenup.client.playback

import androidx.media3.session.CommandButton
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.localization.SystemStrings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The buttons the session offers beside previous / play / next (spec §5.7): back N and forward N on the system media
 * card, Speed too where there is room for it (Android Auto). Android 13+ draws the card from the platform session,
 * which carries the media notification controller's preferences, and shows two custom buttons beside the reserved
 * previous and next slots, so the order decides what the card keeps: back N, forward N, then Speed.
 */
@RunWith(RobolectricTestRunner::class)
class MediaButtonPreferencesTest {
    private val words = SystemStrings.ENGLISH_FALLBACK

    @Test
    fun `the card gets back N and forward N first, with the user's intervals, and Speed after them`() {
        val buttons = mediaButtonsFor(ControllerTrust.MEDIA_NOTIFICATION, backwardSec = 10, forwardSec = 30, words)

        buttons.map { it.sessionCommand?.customAction } shouldBe
            listOf(
                AudiobookNotificationProvider.COMMAND_SKIP_BACK,
                AudiobookNotificationProvider.COMMAND_SKIP_FORWARD,
                CustomActions.CYCLE_SPEED,
            )
        buttons.map { it.displayName.toString() } shouldBe listOf("Back 10s", "Forward 30s", "Speed")
        buttons[0].icon shouldBe CommandButton.ICON_SKIP_BACK_10
        buttons[1].icon shouldBe CommandButton.ICON_SKIP_FORWARD_30
    }

    @Test
    fun `the skips take the secondary and overflow slots, never the previous and next slots`() {
        val buttons = mediaButtonsFor(ControllerTrust.MEDIA_NOTIFICATION, backwardSec = 10, forwardSec = 30, words)

        buttons[0].slots.toArray().toList() shouldBe
            listOf(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
        buttons[1].slots.toArray().toList() shouldBe
            listOf(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
        buttons[2].slots.toArray().toList() shouldBe listOf(CommandButton.SLOT_OVERFLOW)
        buttons.none { it.slots.contains(CommandButton.SLOT_BACK) || it.slots.contains(CommandButton.SLOT_FORWARD) } shouldBe
            true
    }

    @Test
    fun `Android Auto and the app get the same three`() {
        val card = mediaButtonsFor(ControllerTrust.MEDIA_NOTIFICATION, backwardSec = 15, forwardSec = 45, words)

        mediaButtonsFor(ControllerTrust.AUTO_OR_AUTOMOTIVE, 15, 45, words) shouldBe card
        mediaButtonsFor(ControllerTrust.OWN_APP, 15, 45, words) shouldBe card
    }

    @Test
    fun `system controllers we do not know get no custom buttons`() {
        mediaButtonsFor(ControllerTrust.TRUSTED_SYSTEM, 10, 30, words).shouldBeEmpty()
        mediaButtonsFor(ControllerTrust.UNKNOWN, 10, 30, words).shouldBeEmpty()
    }
}
