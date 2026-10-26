package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SleepTimerActionReceiverTest :
    FunSpec({
        class RecordingControls : SleepTimerControls {
            val calls = mutableListOf<String>()

            override fun extendSleepTimer(minutes: Int) {
                calls += "extend $minutes"
            }

            override fun cancelSleepTimer() {
                calls += "cancel"
            }
        }

        test("Add 10 min extends the timer by ten minutes") {
            val controls = RecordingControls()
            handleSleepTimerAction(SleepTimerActionReceiver.ACTION_ADD_TEN, controls)
            controls.calls shouldBe listOf("extend 10")
        }

        test("Cancel and Keep listening both cancel, which during the fade restores the volume") {
            val controls = RecordingControls()
            handleSleepTimerAction(SleepTimerActionReceiver.ACTION_CANCEL, controls)
            handleSleepTimerAction(SleepTimerActionReceiver.ACTION_KEEP_LISTENING, controls)
            controls.calls shouldBe listOf("cancel", "cancel")
        }

        test("an unknown action does nothing") {
            val controls = RecordingControls()
            handleSleepTimerAction("com.example.SOMETHING", controls)
            handleSleepTimerAction(null, controls)
            controls.calls shouldBe emptyList()
        }
    })
