package com.calypsan.listenup.client.navigation

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A hardware keyboard drives the player from anywhere in the app — Space plays or pauses, Left and
 * Right skip by the user's own intervals, `/` or Ctrl+F opens search — but only while focus rests on
 * the shell itself. A focused text field keeps every key it is typing, and a control the user has
 * tabbed or arrowed to keeps Space and the arrows, so keyboard focus navigation still works.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ShellKeyboardShortcutsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fieldFocus = FocusRequester()
    private val buttonFocus = FocusRequester()
    private var typed = ""
    private var buttonClicks = 0

    // ── The key map ──────────────────────────────────────────────────────────

    @Test
    fun `space, the arrows, slash and ctrl+f name their shortcuts`() {
        shellShortcutFor(down(KEYCODE_SPACE)) shouldBe ShellShortcut.PlayPause
        shellShortcutFor(down(KEYCODE_DPAD_LEFT)) shouldBe ShellShortcut.SkipBack
        shellShortcutFor(down(KEYCODE_DPAD_RIGHT)) shouldBe ShellShortcut.SkipForward
        shellShortcutFor(down(KEYCODE_SLASH)) shouldBe ShellShortcut.OpenSearch
        shellShortcutFor(down(KEYCODE_F, META_CTRL_ON or META_CTRL_LEFT_ON)) shouldBe ShellShortcut.OpenSearch
    }

    @Test
    fun `other keys, key releases, modified keys and a held space are not shortcuts`() {
        shellShortcutFor(down(KEYCODE_A)) shouldBe null
        shellShortcutFor(down(KEYCODE_F)) shouldBe null
        shellShortcutFor(KeyEvent(android.view.KeyEvent(ACTION_UP, KEYCODE_SPACE))) shouldBe null
        shellShortcutFor(down(KEYCODE_SPACE, META_CTRL_ON or META_CTRL_LEFT_ON)) shouldBe null
        shellShortcutFor(down(KEYCODE_SLASH, META_SHIFT_ON or META_SHIFT_LEFT_ON)) shouldBe null
        shellShortcutFor(down(KEYCODE_SPACE, repeat = 1)) shouldBe null
    }

    // ── At the shell ─────────────────────────────────────────────────────────

    @Test
    fun `with focus on the shell the keys drive the player and open search`() {
        val dispatched = setShell()

        composeRule.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onRoot().performKeyInput { pressKey(Key.Slash) }
        composeRule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }

        dispatched shouldBe
            listOf(
                ShellShortcut.PlayPause,
                ShellShortcut.SkipBack,
                ShellShortcut.SkipForward,
                ShellShortcut.OpenSearch,
                ShellShortcut.OpenSearch,
            )
    }

    @Test
    fun `a focused text field keeps the keys it is typing`() {
        val dispatched = setShell()

        composeRule.runOnIdle { fieldFocus.requestFocus() }
        composeRule.onRoot().performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.Slash)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
        }

        dispatched.shouldBeEmpty()
        composeRule.runOnIdle { typed shouldBe " /" }
    }

    @Test
    fun `a control reached by keyboard keeps space and the arrows`() {
        val dispatched = setShell()

        composeRule.runOnIdle { buttonFocus.requestFocus() }
        composeRule.onRoot().performKeyInput { pressKey(Key.Spacebar) }

        dispatched.shouldBeEmpty()
        composeRule.runOnIdle { buttonClicks shouldBe 1 }
    }

    @Test
    fun `off, as on a TV, the shell answers nothing and D-pad focus is untouched`() {
        val dispatched = setShell(enabled = false)

        composeRule.onRoot().performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.DirectionLeft)
        }

        dispatched.shouldBeEmpty()
    }

    @Test
    fun `a shortcut the app cannot act on is not consumed`() {
        val dispatched = mutableListOf<ShellShortcut>()
        var reachedOuter = false
        composeRule.setContent {
            Column(
                Modifier.onKeyEvent {
                    reachedOuter = true
                    false
                },
            ) {
                ShellKeyboardShortcuts(
                    enabled = true,
                    onShortcut = { shortcut ->
                        dispatched += shortcut
                        false
                    },
                ) { Text("Library") }
            }
        }

        composeRule.onRoot().performKeyInput { pressKey(Key.Spacebar) }

        dispatched shouldBe listOf(ShellShortcut.PlayPause)
        reachedOuter shouldBe true
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    private fun setShell(enabled: Boolean = true): List<ShellShortcut> {
        val dispatched = mutableListOf<ShellShortcut>()
        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            ShellKeyboardShortcuts(
                enabled = enabled,
                onShortcut = { shortcut ->
                    dispatched += shortcut
                    true
                },
            ) {
                Column {
                    TextField(
                        value = text,
                        onValueChange = { newText ->
                            text = newText
                            typed = newText
                        },
                        modifier = Modifier.focusRequester(fieldFocus).testTag("field"),
                    )
                    Button(onClick = { buttonClicks++ }, modifier = Modifier.focusRequester(buttonFocus)) {
                        Text("Play all")
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return dispatched
    }

    private fun down(
        keyCode: Int,
        metaState: Int = 0,
        repeat: Int = 0,
    ) = KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, keyCode, repeat, metaState))
}
