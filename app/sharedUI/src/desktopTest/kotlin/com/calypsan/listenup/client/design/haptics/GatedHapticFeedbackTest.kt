package com.calypsan.listenup.client.design.haptics

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class RecordingFeedback : HapticFeedback {
    var count = 0

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        count++
    }
}

class GatedHapticFeedbackTest :
    FunSpec({
        test("forwards to the delegate when enabled") {
            val delegate = RecordingFeedback()
            val gated = GatedHapticFeedback(delegate) { true }
            gated.performHapticFeedback(HapticFeedbackType.LongPress)
            delegate.count shouldBe 1
        }

        test("no-ops when disabled") {
            val delegate = RecordingFeedback()
            val gated = GatedHapticFeedback(delegate) { false }
            gated.performHapticFeedback(HapticFeedbackType.LongPress)
            delegate.count shouldBe 0
        }

        test("reads the enabled flag live on every call") {
            val delegate = RecordingFeedback()
            var enabled = false
            val gated = GatedHapticFeedback(delegate) { enabled }
            gated.performHapticFeedback(HapticFeedbackType.SegmentTick) // suppressed
            enabled = true
            gated.performHapticFeedback(HapticFeedbackType.SegmentTick) // forwarded
            delegate.count shouldBe 1
        }
    })
