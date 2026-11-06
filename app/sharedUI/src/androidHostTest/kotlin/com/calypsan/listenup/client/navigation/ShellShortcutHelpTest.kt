package com.calypsan.listenup.client.navigation

import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.META_CTRL_ON
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The system's keyboard-shortcuts helper (Meta+/) lists what the shell answers, so a hardware-keyboard
 * user can find Space, the arrows and search without being told. [ShellShortcut] is the source of truth;
 * this pins the list to it.
 */
@RunWith(RobolectricTestRunner::class)
class ShellShortcutHelpTest {
    private val labels =
        ShellShortcutLabels(playPause = "Play or pause", skipBack = "Skip back", skipForward = "Skip forward", search = "Search")

    @Test
    fun `lists every key the shell answers, search under both of its keys`() {
        val keys = shellShortcutHelp(labels).map { Triple(it.label, it.keycode, it.modifiers) }

        keys.shouldContainExactlyInAnyOrder(
            Triple("Play or pause", KEYCODE_SPACE, 0),
            Triple("Skip back", KEYCODE_DPAD_LEFT, 0),
            Triple("Skip forward", KEYCODE_DPAD_RIGHT, 0),
            Triple("Search", KEYCODE_SLASH, 0),
            Triple("Search", KEYCODE_F, META_CTRL_ON),
        )
    }

    @Test
    fun `covers every ShellShortcut`() {
        // Four shortcuts, five keys: OpenSearch has two. A new ShellShortcut must be added to the help.
        ShellShortcut.entries.size shouldBe 4
        shellShortcutHelp(labels).size shouldBe 5
    }

    @Test
    fun `the labels come from the catalog and are never blank`() =
        runTest {
            val loaded = loadShellShortcutLabels()

            listOf(loaded.playPause, loaded.skipBack, loaded.skipForward, loaded.search).forEach { it.shouldNotBeBlank() }
        }
}
