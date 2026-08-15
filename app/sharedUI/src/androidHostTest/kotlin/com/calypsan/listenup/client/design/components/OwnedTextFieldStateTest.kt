package com.calypsan.listenup.client.design.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Unit-level pin on the echo ledger's bound: [OwnedTextFieldState] tracks every propagated text
 * until its echo returns, so a caller that never echoes (no-op `onValueChange`, pinned value)
 * would otherwise grow the ledger one entry per keystroke forever. The cap evicts the oldest
 * PENDING entry while the adopted head — the pinned caller value — keeps being recognised, so
 * typing is never clobbered.
 */
class OwnedTextFieldStateTest {
    @Test
    fun `the echo ledger stays bounded when echoes never arrive`() {
        val state = OwnedTextFieldState("")
        val keystrokes = MAX_TRACKED_ECHOES * 3

        repeat(keystrokes) { index ->
            val text = "a".repeat(index + 1)
            state.edit(TextFieldValue(text, TextRange(text.length)))
            // Every recomposition still sees the pinned caller value.
            state.reconcile("")
        }

        state.trackedEchoCount shouldBeLessThanOrEqual MAX_TRACKED_ECHOES
        // The pinned value never reads as an external replacement: the typing survives intact.
        state.fieldValue.text shouldBe "a".repeat(keystrokes)
        state.fieldValue.selection shouldBe TextRange(keystrokes)
    }
}
