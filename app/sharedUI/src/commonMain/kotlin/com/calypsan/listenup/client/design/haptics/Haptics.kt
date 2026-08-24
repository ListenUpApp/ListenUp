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

    /**
     * A transport button did its thing — skip, chapter step. Distinct from [selectionTick] (which
     * belongs to continuous gestures) and from [commit] (which is heavier, for actions that
     * complete): a press is light and safe to repeat five times in a row.
     */
    fun press()

    /** A switch/toggle flipped. [on] selects the on vs. off feel. */
    fun toggle(on: Boolean)

    /** A long-press registered (e.g. a context menu is about to open). */
    fun longPress()

    /** A gesture crossed an activation threshold (pull-to-refresh fired, a drag was picked up). */
    fun thresholdActivate()

    /** A deliberate action completed — a download started, a delete confirmed, a book shelved. */
    fun commit()
}

/** [Haptics] backed by a Compose [HapticFeedback]. Gating lives in the supplied [feedback]. */
internal class HapticFeedbackHaptics(
    private val feedback: HapticFeedback,
) : Haptics {
    override fun selectionTick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    // iOS maps this verb to `.impact(weight: .light)` deliberately; the platforms are meant to
    // differ here, so don't "fix" either side into parity.
    override fun press() = feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)

    // ToggleOff is inert on Pixel/Android 17 (verified on device): turning a switch OFF produced no
    // feedback at all, while ToggleOn was felt every time. ContextClick is perceptible on the same
    // hardware and still reads as "a discrete state change occurred", so the off direction confirms
    // itself instead of going silent.
    override fun toggle(on: Boolean) =
        feedback.performHapticFeedback(
            if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ContextClick,
        )

    override fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    override fun thresholdActivate() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    // iOS maps this verb to `.success` — Apple's notification family is what "a task completed"
    // means there. The divergence is the point; don't "fix" either side into parity.
    override fun commit() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

/** A [Haptics] that does nothing — the default on platforms/contexts without haptics (Desktop). */
internal object NoOpHaptics : Haptics {
    override fun selectionTick() = Unit

    override fun press() = Unit

    override fun toggle(on: Boolean) = Unit

    override fun longPress() = Unit

    override fun thresholdActivate() = Unit

    override fun commit() = Unit
}
