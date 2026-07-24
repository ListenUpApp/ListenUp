package com.calypsan.listenup.client.design.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class RecordingHapticFeedback : HapticFeedback {
    val performed = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        performed += hapticFeedbackType
    }
}

class HapticFeedbackHapticsTest :
    FunSpec({
        test("each verb maps to the expected HapticFeedbackType") {
            val recorder = RecordingHapticFeedback()
            val haptics = HapticFeedbackHaptics(recorder)

            haptics.selectionTick()
            haptics.toggle(on = true)
            haptics.toggle(on = false)
            haptics.longPress()
            haptics.thresholdActivate()
            haptics.commit()

            recorder.performed shouldBe
                listOf(
                    HapticFeedbackType.SegmentTick,
                    HapticFeedbackType.ToggleOn,
                    HapticFeedbackType.ToggleOff,
                    HapticFeedbackType.LongPress,
                    HapticFeedbackType.GestureThresholdActivate,
                    HapticFeedbackType.Confirm,
                )
        }
    })
