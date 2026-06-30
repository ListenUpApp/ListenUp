package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Delegation tests for [AndroidPlaybackController].
 *
 * [androidx.media3.session.MediaController] is a final Android framework class that
 * cannot be instantiated in JVM host tests. Tests therefore cover:
 *
 * 1. acquire/release delegate to [ControllerHolder]
 * 2. isReady mirrors holder.isConnected
 * 3. All command methods are silent no-ops when controller is null (no crash)
 * 4. resolveQueuePosition index and offset arithmetic
 */
class AndroidPlaybackControllerTest :
    FunSpec({
        // ---------------------------------------------------------------------------
        // acquire / release
        // ---------------------------------------------------------------------------

        test("acquire delegates to holder") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.acquire()
            sut.acquire()

            holder.acquireCount shouldBe 2
        }

        test("release delegates to holder") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.releasePlayer()

            holder.releaseCount shouldBe 1
        }

        // ---------------------------------------------------------------------------
        // isReady mirrors isConnected
        // ---------------------------------------------------------------------------

        test("isReady reflects holder isConnected initial value true") {
            val holder = FakeControllerHolder(initialConnected = true)
            val sut = AndroidPlaybackController(holder)

            sut.isReady.value shouldBe true
        }

        test("isReady reflects holder isConnected initial value false") {
            val holder = FakeControllerHolder(initialConnected = false)
            val sut = AndroidPlaybackController(holder)

            sut.isReady.value shouldBe false
        }

        test("isReady updates when holder isConnected changes") {
            val holder = FakeControllerHolder(initialConnected = true)
            val sut = AndroidPlaybackController(holder)

            holder.setConnected(false)

            sut.isReady.value shouldBe false
        }

        // ---------------------------------------------------------------------------
        // Null-controller silent no-ops
        // ---------------------------------------------------------------------------

        test("play does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.play() // holder.controller == null — must not throw
        }

        test("pause does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.pause()
        }

        test("seekTo does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.seekTo(30_000L)
        }

        test("setPlaybackSpeed does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.setPlaybackSpeed(1.5f)
        }

        test("setMediaQueue does not throw when controller is null") {
            runTest {
                val holder = FakeControllerHolder()
                val sut = AndroidPlaybackController(holder)

                sut.setMediaQueue(emptyList(), 0L)
            }
        }

        // ---------------------------------------------------------------------------
        // stop / setVolume — null-controller silent no-ops
        // ---------------------------------------------------------------------------

        test("stop does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.stop() // holder.controller == null — must not throw
        }

        test("setVolume does not throw when controller is null") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.setVolume(0.5f) // holder.controller == null — must not throw
        }

        // ---------------------------------------------------------------------------
        // resolveQueuePosition — pure arithmetic, no Media3 needed
        // Used by both setMediaQueue and seekTo call sites.
        // ---------------------------------------------------------------------------

        test("resolveQueuePosition returns 0 0 for empty item list") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            sut.resolveQueuePosition(emptyList(), 0L) shouldBe (0 to 0L)
            sut.resolveQueuePosition(emptyList(), 12_345L) shouldBe (0 to 0L)
        }

        test("resolveQueuePosition maps bookPosition to correct segment index and local offset") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

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
            val sut = AndroidPlaybackController(holder)

            val items =
                listOf(
                    PlaybackMediaItem("f1", "/1", null, 60_000L, 100L, "T", null, null, null),
                )

            sut.resolveQueuePosition(items, 0L) shouldBe (0 to 0L)
            sut.resolveQueuePosition(items, 50L) shouldBe (0 to 0L) // before offsetMs=100
        }

        test("resolveQueuePosition past last item snaps to lastIndex with last item duration (drift 26 fix)") {
            val holder = FakeControllerHolder()
            val sut = AndroidPlaybackController(holder)

            val items =
                listOf(
                    PlaybackMediaItem("f1", "/1", null, 60_000L, 0L, "T", null, null, null),
                    PlaybackMediaItem("f2", "/2", null, 90_000L, 60_000L, "T", null, null, null),
                )

            // Total duration = 150_000. seekTo(200_000) — past end.
            // Should return (1, 90_000L) — LAST item's durationMs, not controller.duration
            sut.resolveQueuePosition(items, 200_000L) shouldBe (1 to 90_000L)
        }

        test("seekTo with empty cached queue does not throw and falls back gracefully") {
            val holder = FakeControllerHolder() // controller is always null
            val sut = AndroidPlaybackController(holder)

            // No setMediaQueue call — cachedQueue is empty. Controller is null so the
            // null-check fires first; either way the call must not throw.
            sut.seekTo(5_000L)
        }
    })

// ---------------------------------------------------------------------------
// File-private fake — ControllerHolder implementation
// ---------------------------------------------------------------------------

private class FakeControllerHolder(
    initialConnected: Boolean = true,
) : ControllerHolder {
    var acquireCount = 0
    var releaseCount = 0
    private val _isConnected = MutableStateFlow(initialConnected)
    override val isConnected: StateFlow<Boolean> = _isConnected

    /** Always null — MediaController cannot be instantiated in JVM host tests. */
    override val controller: androidx.media3.session.MediaController? = null

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
    }

    fun setConnected(value: Boolean) {
        _isConnected.value = value
    }
}
