package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the transport-command policy in [playerCommandsFor].
 *
 * Media3 1.11 added `DEFAULT_UNTRUSTED_PLAYER_COMMANDS` and changed `onConnect` to hand untrusted
 * controllers read-only access by default. We had never called `setAvailablePlayerCommands`, so the
 * bump could have revoked play/pause/seek from Android Auto, Wear or the media notification —
 * silently, with no crash and no compile error. The symptom would have been buttons that do nothing.
 *
 * [ControllerTrust] gates custom commands, custom layout and browse. It has never gated transport,
 * and this pins that so a future default change cannot quietly make it do so.
 *
 * Robolectric rather than a plain Kotest spec because `Player.Commands.Builder`'s static
 * initializer needs the Android framework — the sibling [ControllerGatingTest] stays framework-free
 * precisely because its subject, [controllerTrustOf], is pure.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerCommandPolicyTest {
    @Test
    fun `every trust level keeps transport control`() {
        ControllerTrust.entries.forEach { trust ->
            val commands = playerCommandsFor(trust)

            withClue(trust.name) {
                commands.contains(Player.COMMAND_PLAY_PAUSE) shouldBe true
                commands.contains(Player.COMMAND_SEEK_BACK) shouldBe true
                commands.contains(Player.COMMAND_SEEK_FORWARD) shouldBe true
            }
        }
    }
}
