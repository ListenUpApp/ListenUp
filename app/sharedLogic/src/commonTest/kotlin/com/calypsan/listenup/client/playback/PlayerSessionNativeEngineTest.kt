package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.preparedPlaybackFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The session over the iOS engine: Kotlin prepares, Swift's facts come back tagged with the load they belong to. */
class PlayerSessionNativeEngineTest :
    FunSpec({
        /** A rig whose engine is a real [NativePlayerEngine] that prepares every book successfully, into [prepared]. */
        fun TestScope.nativeRig(
            prepared: MutableList<Pair<BookId, PlaybackStart>> = mutableListOf(),
        ): Pair<PlayerSessionRig, () -> NativePlayerEngine> {
            var engine: NativePlayerEngine? = null
            val rig =
                PlayerSessionRig(this, playerEngine = { scope ->
                    NativePlayerEngine(
                        prepare = { id, start ->
                            prepared += id to start
                            AppResult.Success(preparedPlaybackFor(id.value, durationMs = 300_000))
                        },
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

        test("a mid-play failure dismissed to Paused plays again through a fresh prepare") {
            runTest {
                val prepared = mutableListOf<Pair<BookId, PlaybackStart>>()
                val (rig, engine) = nativeRig(prepared)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    engine().reportConnectionLost(1, "NSURLErrorDomain -1005")
                    engine().onPhase(1, EnginePhase.PAUSED)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost(debugInfo = "NSURLErrorDomain -1005"))
                    rig.session.dismissError()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    rig.session.playPause()
                    runCurrent()
                    prepared shouldBe listOf(BookId("a") to PlaybackStart.Resume, BookId("a") to PlaybackStart.Resume)
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                    engine().onPhase(2, EnginePhase.PLAYING)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed switch to a never-played book shows and retries from that book's place, never the last book's") {
            runTest {
                val prepared = mutableListOf<Pair<BookId, PlaybackStart>>()
                val (rig, engine) = nativeRig(prepared)
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().onPhase(1, EnginePhase.PLAYING)
                    engine().onPosition(1, 250_000)
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    // b's stream dies before its first byte; a late tick from a's load is about a book no longer held.
                    engine().onPosition(1, 250_400)
                    engine().reportConnectionLost(2, "NSURLErrorDomain -1004")
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b")
                    rig.session.positionMs.value shouldBe 0L
                    rig.session.retry()
                    runCurrent()
                    prepared.last() shouldBe (BookId("b") to PlaybackStart.Resume)
                } finally {
                    rig.close()
                }
            }
        }

        test("a prepare that throws on iOS stays Failed: the engine never adopted the book") {
            runTest {
                var engine: NativePlayerEngine? = null
                val rig =
                    PlayerSessionRig(this, playerEngine = { scope ->
                        NativePlayerEngine(
                            prepare = { id, _ ->
                                if (id == BookId("b")) error("the stream could not be prepared")
                                AppResult.Success(preparedPlaybackFor(id.value, durationMs = 300_000))
                            },
                            scope = scope,
                        ).also { engine = it }
                    })
                try {
                    rig.withBook("a")
                    rig.withBook("b")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine!!.onPhase(1, EnginePhase.PLAYING)
                    rig.session.playBook(BookId("b"), null)
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b")
                    rig.loaded.phase.shouldBeInstanceOf<PlayerPhase.Failed>()
                } finally {
                    rig.close()
                }
            }
        }

        test("Retry on a mid-play failure re-prepares through the session") {
            runTest {
                val prepared = mutableListOf<Pair<BookId, PlaybackStart>>()
                val (rig, engine) = nativeRig(prepared)
                try {
                    rig.withBook("a")
                    runCurrent()
                    rig.session.playBook(BookId("a"), null)
                    runCurrent()
                    engine().reportCouldNotStart(1, null)
                    runCurrent()
                    rig.session.playPause()
                    runCurrent()
                    prepared.size shouldBe 2
                    rig.loaded.phase shouldBe PlayerPhase.Buffering
                } finally {
                    rig.close()
                }
            }
        }
    })
