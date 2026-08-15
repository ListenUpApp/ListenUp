package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Local text-and-caret ownership for the design system's `String`-API text fields.
 *
 * The fields keep a `String` contract with their callers, but that value round-trips
 * asynchronously (field → ViewModel `StateFlow` → recomposition), so a recomposition can arrive
 * carrying a STALE string while the user is still typing — and Compose's `String`-overload text
 * fields treat the incoming string as authoritative, clamping the caret into it and resetting the
 * edit buffer against it. Holding the [TextFieldValue] here keeps text and selection in one
 * synchronously updated value, and [reconcile] distinguishes the echo of the user's own keystrokes
 * (a no-op — the caret is never touched) from a genuine external replacement (adopted with the
 * caret at the end of the new text).
 *
 * Three contract edges are worth knowing:
 *
 * **Echo-verbatim contract.** A caller that echoes must echo the propagated string VERBATIM, or
 * not echo at all. A caller that needs to transform input (trim, uppercase, normalisation) must
 * transform inside the component before propagating, the way `CodeBoxes` does — a transforming
 * caller makes every echo read as an external replacement and silently drops in-flight
 * keystrokes under latency, the exact bug class this class exists to fix.
 *
 * **Ordering assumption.** Callers must deliver value updates in order — a conflated `StateFlow`
 * collected into snapshot state (this codebase's ViewModel wiring) qualifies: conflation can SKIP
 * intermediate values but never REORDER them, so a stale echo cannot arrive after a newer
 * replacement. A caller wired to a transport that can reorder deliveries breaks [reconcile]'s
 * stale-echo detection.
 *
 * **Ambiguity window.** A genuine external replacement whose text still sits in the in-flight
 * ledger (a value the user typed through moments ago) is indistinguishable from a stale echo and
 * is ignored until the ledger drains. The window is one echo round-trip wide and self-heals on
 * the next differing caller value.
 */
@Stable
internal class OwnedTextFieldState(
    initial: String,
) {
    /** The value the underlying text field renders — the local truth for text and selection. */
    var fieldValue: TextFieldValue by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    /**
     * Texts whose arrival from the caller must not reset the field: the last value adopted from
     * the caller, plus every text propagated whose (possibly conflated) echo has not come back
     * yet. Anything outside this set is a genuine external replacement.
     */
    private val expectedEchoes = ArrayDeque<String>().apply { add(initial) }

    /**
     * Folds the caller's current [value] into local state. A value equal to the local text is the
     * settled echo; a value still in [expectedEchoes] is a stale echo of the user's own typing —
     * neither touches the caret. Anything else is an external replacement (metadata fetch,
     * ViewModel-side clear): adopt it with the caret at the end.
     */
    fun reconcile(value: String) {
        if (value == fieldValue.text) {
            settle(value)
            return
        }
        val staleIndex = expectedEchoes.indexOf(value)
        if (staleIndex >= 0) {
            // A late echo of our own keystroke — anything older can no longer arrive.
            repeat(staleIndex) { expectedEchoes.removeFirst() }
            return
        }
        fieldValue = TextFieldValue(value, TextRange(value.length))
        settle(value)
    }

    /**
     * Records a user edit from the field. Returns true when the TEXT changed — the `String`
     * contract fires only then, so selection-only changes (taps, arrow keys) stay local, exactly
     * as the `String` overload behaved.
     */
    fun edit(newValue: TextFieldValue): Boolean {
        val textChanged = newValue.text != fieldValue.text
        fieldValue = newValue
        if (textChanged) {
            expectedEchoes.add(newValue.text)
            // Never evict the head: it is the newest caller value already acknowledged, and a
            // never-echoing caller's pinned value must keep being recognised or the next
            // reconcile would read it as an external replacement and clobber the typing.
            while (expectedEchoes.size > MAX_TRACKED_ECHOES) expectedEchoes.removeAt(1)
        }
        return textChanged
    }

    /** How many texts the ledger currently tracks (adopted head + pending echoes). For tests. */
    internal val trackedEchoCount: Int get() = expectedEchoes.size

    private fun settle(value: String) {
        expectedEchoes.clear()
        expectedEchoes.add(value)
    }
}

/**
 * Upper bound on the echo ledger. A well-behaved ViewModel echoes within a frame or two, so the
 * ledger normally holds one or two entries — this many unacknowledged keystrokes means the caller
 * is not echoing at all. Overflow evicts the oldest PENDING entry (never the adopted head),
 * degrading gracefully toward the naive reset-on-difference behavior only for values too old to
 * be honest echoes.
 */
internal const val MAX_TRACKED_ECHOES: Int = 32

/**
 * Remembers an [OwnedTextFieldState] seeded from [value] (caret at the end — so first focus of a
 * pre-populated field starts at the end, not index 0) and reconciles it against the caller's
 * current [value] on every composition.
 */
@Composable
internal fun rememberOwnedTextFieldState(value: String): OwnedTextFieldState {
    val state = remember { OwnedTextFieldState(value) }
    state.reconcile(value)
    return state
}
