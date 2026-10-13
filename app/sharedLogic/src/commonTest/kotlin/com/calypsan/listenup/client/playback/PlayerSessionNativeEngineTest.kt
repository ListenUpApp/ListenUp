package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The session over the iOS engine: Kotlin prepares, Swift's facts come back tagged with the load they belong to. */
class PlayerSessionNativeEngineTest :
    FunSpec({
        /** A rig whose engine is a real [NativePlayerEngine] that prepares every book successfully. */
        fun TestScope.nativeRig(): Pair<PlayerSessionRig, () -> NativePlayerEngine> {
            var engine: NativePlayerEngine? = null
            val rig =
                PlayerSessionRig(this, playerEngine = { scope ->
                    NativePlayerEngine(
                        prepare = { id, _ -> AppResult.Success(preparedPlaybackFor(id.value, durationMs = 300_000)) },
                        scope = scope,
                    ).also { engine = it }
                })
            return rig to { engine!! }
        }

        test("a late failure from the book being left does not fail the book being opened") {
            runTest {
                val (rig, engine) = nativeRig()
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    // Swift is still on a's load (generation 1) when a's deleted file fails under it.
                    engine().reportCouldNotStart(1, "a's file is gone")
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b")
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }
    })
