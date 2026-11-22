package com.calypsan.listenup.client.download

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The notification's Pause and Cancel reach the download controls for the book they name, and nothing else does. */
class DownloadActionReceiverTest :
    FunSpec({
        class Recorder : DownloadNotificationControls {
            val calls = mutableListOf<String>()

            override suspend fun pause(bookId: BookId) {
                calls += "pause ${bookId.value}"
            }

            override suspend fun cancel(bookId: BookId) {
                calls += "cancel ${bookId.value}"
            }
        }

        test("Pause pauses the book, keeping what it saved") {
            val controls = Recorder()
            handleDownloadAction(DownloadActionReceiver.ACTION_PAUSE, "b1", controls)
            controls.calls shouldBe listOf("pause b1")
        }

        test("Cancel cancels the book without asking") {
            val controls = Recorder()
            handleDownloadAction(DownloadActionReceiver.ACTION_CANCEL, "b1", controls)
            controls.calls shouldBe listOf("cancel b1")
        }

        test("an unknown action, or one with no book, does nothing") {
            val controls = Recorder()
            handleDownloadAction("com.example.OTHER", "b1", controls)
            handleDownloadAction(DownloadActionReceiver.ACTION_CANCEL, null, controls)
            controls.calls shouldBe emptyList()
        }
    })
