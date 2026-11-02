package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * An engine that lets go of its book (Android's idle timeout releasing the player) leaves the book loaded and
 * restorable: the bar shows it Restored, the loaded key stands, and Play re-prepares from the saved place.
 */
class PlayerSessionEngineReleaseTest :
    FunSpec({
        test("an engine that releases its paused book leaves it Restored, keyed, and Play re-prepares from Resume") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.session.playPause()
                    runCurrent()
                    rig.loaded.isEngineBound shouldBe true

                    rig.engine.loadedBookId.value = null
                    rig.engine.phase.value = EnginePhase.IDLE
                    runCurrent()

                    rig.loaded.bookId shouldBe BookId("b1")
                    rig.loaded.isEngineBound shouldBe false
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                    rig.store.stored shouldBe "b1"

                    rig.engine.loads.clear()
                    rig.session.playPause()
                    runCurrent()
                    rig.engine.loads shouldBe listOf(BookId("b1") to PlaybackStart.Resume)
                } finally {
                    rig.close()
                }
            }
        }
    })
