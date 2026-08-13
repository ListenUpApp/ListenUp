package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Delegation tests for [AndroidPlaybackController].
 *
 * [androidx.media3.session.MediaController] has no public or internal constructor (verified via
 * `javap` against the media3-session jar — it is not `final`, but its only constructor is
 * package-private inside `androidx.media3.session`), so it cannot be instantiated *or mocked* in
 * a JVM host test. Tests therefore cover:
 *
 * 1. acquire/release delegate to [ControllerHolder]
 * 2. isReady mirrors holder.isConnected
 * 3. All command methods are no-throw when the controller never becomes available (bounded
 *    [ControllerHolder.awaitController] exhausts its bound and resolves to null) — including
 *    [AndroidPlaybackController.seekTo], which now sends a `SEEK_TO_BOOK_POSITION` custom
 *    command rather than resolving a cached queue; the null-controller guard fires first either
 *    way, so no separate coverage of the custom-command send itself lives here (it's adapter
 *    glue over a non-instantiable [MediaController], the same reasoning as `ChapterWindowPlayer`'s
 *    "No androidHostTest for this class" KDoc).
 * 4. Commands await the controller via [ControllerHolder.awaitController] rather than reading a
 *    stale `holder.controller` snapshot — the F6 regression (see `MediaControllerHolderTest` for
 *    the underlying bounded-wait mechanism, which is where the "reaches it once it binds"
 *    property is actually proven, since a real controller value can't be produced here).
 * 5. resolveQueuePosition index and offset arithmetic (now [AndroidPlaybackController.setMediaQueue]'s
 *    sole caller — see [AndroidPlaybackController.seekTo]'s KDoc for why seeks no longer use it)
 *
 * [AndroidPlaybackController.buildMediaItems] (artwork URI mapping) is covered separately in
 * [AndroidPlaybackControllerArtworkTest] — it routes through `CoverUri.forBook`, which builds a
 * real [android.net.Uri] and therefore needs a Robolectric runtime, which this Kotest spec
 * deliberately does not carry.
 */
class AndroidPlaybackControllerTest :
    FunSpec({
        // ---------------------------------------------------------------------------
        // acquire / release
        // ---------------------------------------------------------------------------

        test("acquire delegates to holder") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            sut.acquire()
            sut.acquire()

            holder.acquireCount shouldBe 2
        }

        test("release delegates to holder") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            sut.releasePlayer()

            holder.releaseCount shouldBe 1
        }

        // ---------------------------------------------------------------------------
        // isReady mirrors isConnected
        // ---------------------------------------------------------------------------

        test("isReady reflects holder isConnected initial value true") {
            val holder = FakeControllerHolder(initialConnected = true)
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            sut.isReady.value shouldBe true
        }

        test("isReady reflects holder isConnected initial value false") {
            val holder = FakeControllerHolder(initialConnected = false)
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            sut.isReady.value shouldBe false
        }

        test("isReady updates when holder isConnected changes") {
            val holder = FakeControllerHolder(initialConnected = true)
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            holder.setConnected(false)

            sut.isReady.value shouldBe false
        }

        // ---------------------------------------------------------------------------
        // Null-controller: awaitController() exhausting its bound must not throw —
        // the user-visible failure is reported by MediaControllerHolder.awaitController()
        // itself (see MediaControllerHolderTest), not by these command methods.
        // ---------------------------------------------------------------------------

        test("play does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.play() // holder.awaitController() resolves to null — must not throw
                advanceUntilIdle()
            }
        }

        test("pause does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.pause()
                advanceUntilIdle()
            }
        }

        test("seekTo does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.seekTo(30_000L)
                advanceUntilIdle()
            }
        }

        test("setPlaybackSpeed does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setPlaybackSpeed(1.5f)
                advanceUntilIdle()
            }
        }

        test("setMediaQueue does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setMediaQueue(emptyList(), 0L)
            }
        }

        // ---------------------------------------------------------------------------
        // stop / setVolume — same bounded-await, no-throw contract
        // ---------------------------------------------------------------------------

        test("stop does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.stop()
                advanceUntilIdle()
            }
        }

        test("setVolume does not throw when the controller never becomes available") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setVolume(0.5f)
                advanceUntilIdle()
            }
        }

        // ---------------------------------------------------------------------------
        // Commands await the controller via ControllerHolder.awaitController() instead
        // of reading a stale holder.controller snapshot (the F6 regression: a command
        // issued before the controller binds must still reach it once it binds).
        // ---------------------------------------------------------------------------

        test("play awaits the controller via awaitController rather than a stale snapshot read") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.play()
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("pause awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.pause()
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("seekTo awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.seekTo(30_000L)
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("setPlaybackSpeed awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setPlaybackSpeed(1.5f)
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("stop awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.stop()
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("setVolume awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setVolume(0.5f)
                advanceUntilIdle()

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("setMediaQueue awaits the controller via awaitController") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.setMediaQueue(emptyList(), 0L)

                holder.awaitControllerCallCount shouldBe 1
            }
        }

        test("play waits for a controller that binds after a delay, instead of giving up on a stale null snapshot (F6)") {
            runTest {
                val holder = FakeControllerHolder().apply { awaitControllerDelayMs = 5_000L }
                val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client", scope = this)

                sut.play()

                // The await hasn't resolved yet — proves play() is genuinely waiting on the
                // controller rather than having already read a stale null and given up, which
                // is exactly the F6 bug (startPlayback silently no-op'd on a cold-start race).
                testScheduler.runCurrent()
                holder.awaitControllerCallCount shouldBe 1

                advanceUntilIdle() // let the delayed await resolve; must not hang or throw
            }
        }

        // ---------------------------------------------------------------------------
        // resolveQueuePosition — pure arithmetic, no Media3 needed
        // Used by setMediaQueue only — seekTo rides the SEEK_TO_BOOK_POSITION custom
        // command instead (see AndroidPlaybackController.seekTo's KDoc).
        // ---------------------------------------------------------------------------

        test("resolveQueuePosition returns 0 0 for empty item list") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            sut.resolveQueuePosition(emptyList(), 0L) shouldBe (0 to 0L)
            sut.resolveQueuePosition(emptyList(), 12_345L) shouldBe (0 to 0L)
        }

        test("resolveQueuePosition maps bookPosition to correct segment index and local offset") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            val items =
                listOf(
                    PlaybackMediaItem("f1", "/1", null, 60_000L, 0L, "T", null, null, null),
                    PlaybackMediaItem("f2", "/2", null, 90_000L, 60_000L, "T", null, null, null),
                )

            // Position 75_000 → second item, offset 15_000
            sut.resolveQueuePosition(items, 75_000L) shouldBe (1 to 15_000L)

            // Position 0 → first item, offset 0
            sut.resolveQueuePosition(items, 0L) shouldBe (0 to 0L)

            // Position 30_000 → first item, offset 30_000
            sut.resolveQueuePosition(items, 30_000L) shouldBe (0 to 30_000L)
        }

        test("resolveQueuePosition before first item snaps to 0 0") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            val items =
                listOf(
                    PlaybackMediaItem("f1", "/1", null, 60_000L, 100L, "T", null, null, null),
                )

            sut.resolveQueuePosition(items, 0L) shouldBe (0 to 0L)
            sut.resolveQueuePosition(items, 50L) shouldBe (0 to 0L) // before offsetMs=100
        }

        test("resolveQueuePosition past last item snaps to lastIndex with last item duration (drift 26 fix)") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder, "com.calypsan.listenup.client")

            val items =
                listOf(
                    PlaybackMediaItem("f1", "/1", null, 60_000L, 0L, "T", null, null, null),
                    PlaybackMediaItem("f2", "/2", null, 90_000L, 60_000L, "T", null, null, null),
                )

            // Total duration = 150_000. seekTo(200_000) — past end.
            // Should return (1, 90_000L) — LAST item's durationMs, not controller.duration
            sut.resolveQueuePosition(items, 200_000L) shouldBe (1 to 90_000L)
        }
    })

// ---------------------------------------------------------------------------
// Fake — ControllerHolder implementation
//
// `internal`, not `private`: also used by AndroidPlaybackControllerArtworkTest, the
// Robolectric-backed sibling spec covering buildMediaItems' artwork URI mapping.
// ---------------------------------------------------------------------------

internal class FakeControllerHolder(
    initialConnected: Boolean = true,
) : ControllerHolder {
    var acquireCount = 0
    var releaseCount = 0
    private val _isConnected = MutableStateFlow(initialConnected)
    override val isConnected: StateFlow<Boolean> = _isConnected

    /** Always null — MediaController cannot be instantiated in JVM host tests. */
    override val controller: androidx.media3.session.MediaController? = null

    /** Simulates the controller binding after a delay — the F6 cold-start race. */
    var awaitControllerDelayMs: Long = 0L

    var awaitControllerCallCount = 0
        private set

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
    }

    override suspend fun awaitController(): androidx.media3.session.MediaController? {
        awaitControllerCallCount++
        if (awaitControllerDelayMs > 0) {
            delay(awaitControllerDelayMs)
        }
        return controller
    }

    fun setConnected(value: Boolean) {
        _isConnected.value = value
    }
}
