package com.calypsan.listenup.client.design.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The app's semantic haptic vocabulary. Call sites express *intent* (a selection moved, a
 * toggle flipped) rather than a raw [HapticFeedbackType], keeping haptics consistent and
 * "subtle but tactile" across the app. Obtain an instance via `LocalHaptics.current`.
 */
interface Haptics {
    /** A discrete value changed during a continuous gesture (scrubber, picker, alphabet index). */
    fun selectionTick()

    /** A switch/toggle flipped. [on] selects the on vs. off feel. */
    fun toggle(on: Boolean)

    /** A long-press registered (e.g. a context menu is about to open). */
    fun longPress()

    /** A gesture crossed an activation threshold (pull-to-refresh fired, a drag was picked up). */
    fun thresholdActivate()

    /** A deliberate action committed (a swipe action, a sleep timer firing). */
    fun commit()
}

/** [Haptics] backed by a Compose [HapticFeedback]. Gating lives in the supplied [feedback]. */
internal class HapticFeedbackHaptics(
    private val feedback: HapticFeedback,
) : Haptics {
    override fun selectionTick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    override fun toggle(on: Boolean) =
        feedback.performHapticFeedback(
            if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )

    override fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    override fun thresholdActivate() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    override fun commit() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

/** A [Haptics] that does nothing — the default on platforms/contexts without haptics (Desktop). */
internal object NoOpHaptics : Haptics {
    override fun selectionTick() = Unit

    override fun toggle(on: Boolean) = Unit

    override fun longPress() = Unit

    override fun thresholdActivate() = Unit

    override fun commit() = Unit
}
