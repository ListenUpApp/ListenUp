package com.calypsan.listenup.client.playback

import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.CodecParametersChangeListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for [PlaybackErrorHandler].
 *
 * [PlaybackException] internally calls [android.os.SystemClock.elapsedRealtime] during
 * construction, which is only available on device or via Robolectric shadow. This test
 * therefore uses [RobolectricTestRunner] + JUnit4 (consistent with [DeepLinkParserTest]).
 * The `junit-vintage-engine` on the classpath keeps these discoverable on the JUnit5 platform
 * alongside Kotest specs in `androidHostTest`.
 *
 * Coverage:
 * - `classify()` — every branch: Network, AuthExpired (401/403), NotFound (404), server-5xx,
 *   Codec, Stuck, Unknown, and the NPE-safe HTTP-status extraction when `cause` is
 *   NOT an [HttpDataSource.InvalidResponseCodeException].
 * - `handle()` — decision logic and return values for Network, AuthExpired, NotFound, Codec,
 *   Stuck, Unknown, including position-save verification and player interaction recording.
 *
 * AuthExpired in `handle()` was long uncovered here, and that is where a loop-forever bug lived:
 * the branch depended on the concrete [AndroidAudioTokenProvider], whose delegate methods are not
 * `open`, and building a real [CachedAudioTokenProvider] is non-deterministic because its
 * constructor launches a background refresh. [PlaybackErrorHandler] now holds only the narrow
 * [AudioTokenRecovery] seam, which [FakeAudioTokenRecovery] implements — so the recovery path is
 * exercised directly rather than declared a coverage gap.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class PlaybackErrorHandlerTest {
    // ── classify — Network ────────────────────────────────────────────────────

    @Test
    fun `classify returns Network for ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`() {
        val error = PlaybackException("net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Network>()
    }

    @Test
    fun `classify returns Network for ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`() {
        val error = PlaybackException("timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Network>()
    }

    // ── classify — HTTP status codes ─────────────────────────────────────────

    @Test
    fun `classify returns AuthExpired for HTTP 401`() {
        val error = makeHttpStatusException(401)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.AuthExpired>()
    }

    @Test
    fun `classify returns AuthExpired for HTTP 403`() {
        val error = makeHttpStatusException(403)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.AuthExpired>()
    }

    @Test
    fun `classify returns NotFound for HTTP 404`() {
        val error = makeHttpStatusException(404)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.NotFound>()
    }

    @Test
    fun `classify returns Network for HTTP 500`() {
        val error = makeHttpStatusException(500)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Network>()
    }

    @Test
    fun `classify returns Network for HTTP 599`() {
        val error = makeHttpStatusException(599)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Network>()
    }

    @Test
    fun `classify returns Unknown for HTTP 400 (unclassified)`() {
        val error = makeHttpStatusException(400)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Unknown>()
    }

    /**
     * NPE-safety: IO_BAD_HTTP_STATUS with a non-[HttpDataSource.InvalidResponseCodeException]
     * cause must not crash. The safe cast must yield null → falls through to `else -> Unknown`.
     */
    @Test
    fun `classify does not crash when cause is not InvalidResponseCodeException`() {
        val error =
            PlaybackException(
                "bad http",
                RuntimeException("wrong cause type"),
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Unknown>()
    }

    /**
     * NPE-safety: IO_BAD_HTTP_STATUS with null cause must not crash.
     */
    @Test
    fun `classify does not crash when cause is null for IO_BAD_HTTP_STATUS`() {
        val error = PlaybackException("bad http null cause", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Unknown>()
    }

    // ── classify — Codec ──────────────────────────────────────────────────────

    @Test
    fun `classify returns Codec for ERROR_CODE_DECODER_INIT_FAILED`() {
        val error = PlaybackException("decoder init", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Codec>()
    }

    @Test
    fun `classify returns Codec for ERROR_CODE_DECODING_FAILED`() {
        val error = PlaybackException("decoding", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Codec>()
    }

    @Test
    fun `classify returns Codec for ERROR_CODE_AUDIO_TRACK_INIT_FAILED`() {
        val error = PlaybackException("audio track", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Codec>()
    }

    // ── classify — Stuck ──────────────────────────────────────────────────────

    @Test
    fun `classify returns Stuck for ERROR_CODE_TIMEOUT`() {
        val error = PlaybackException("timeout", null, PlaybackException.ERROR_CODE_TIMEOUT)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Stuck>()
    }

    // ── classify — Unknown ────────────────────────────────────────────────────

    @Test
    fun `classify returns Unknown for unrecognised error code`() {
        val error = PlaybackException("other", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        makeHandler().classify(error).shouldBeInstanceOf<PlaybackErrorHandler.ClassifiedError.Unknown>()
    }

    // ── handle — Network ──────────────────────────────────────────────────────

    @Test
    fun `handle Network returns true and does not pause player`() {
        runTest {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 8_000L)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    // Book-relative position (file 8 offset 12min): deliberately different from
                    // the FILE-relative player.currentPosition (8_000) to prove the handler
                    // persists the book-relative value passed in, not player.currentPosition.
                    bookPositionMs = 9_012_000L,
                    onShowError = { errors += it },
                )

            withClue("Network error should return true (recovery attempted)") { result shouldBe true }
            withClue("Player should NOT be paused for network errors") { player.pauseCount shouldBe 0 }
            withClue("No user-visible error for transient network issues") { errors.isEmpty() shouldBe true }
            withClue("Position saved once before handling") { tracker.savePlaybackStateCalls.size shouldBe 1 }
            tracker.savePlaybackStateCalls.first().first shouldBe BookId("book1")
            withClue("Saved position must be the BOOK-relative value, not player.currentPosition (8_000)") {
                val saved = tracker.savePlaybackStateCalls.first().second
                saved.shouldBeInstanceOf<PlaybackUpdate.PeriodicUpdate>().positionMs shouldBe 9_012_000L
            }
        }
    }

    @Test
    fun `handle Network skips position save when currentBookId is null`() {
        runTest {
            val tracker = FakeProgressTracker()

            makeHandler(tracker = tracker).handle(
                error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                player = FakeExoPlayer(),
                currentBookId = null,
                bookPositionMs = 0L,
                onShowError = {},
            )

            withClue("No save when bookId is null") { tracker.savePlaybackStateCalls.size shouldBe 0 }
        }
    }

    // ── handle — recovery budget ──────────────────────────────────────────────
    //
    // These cover the fix for a stall that stranded a listener mid-book. The Network branch used
    // to log "ExoPlayer handles retry internally" and return true without touching the player —
    // but ExoPlayer's own retries are already spent by the time onPlayerError fires, so the
    // player sat IDLE forever while the handler reported success.
    //
    // runTest (not runBlocking) so the recovery backoff runs on virtual time.

    @Test
    fun `handle Network re-prepares and resumes the player`() =
        runTest {
            val player = FakeExoPlayer()

            val result =
                makeHandler().handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = {},
                )

            withClue("Recovery attempted, so playback continues") { result shouldBe true }
            withClue("prepare() is what actually lifts the player out of IDLE") {
                player.prepareCount shouldBe 1
            }
            withClue("play() resumes once prepared") { player.playCount shouldBe 1 }
        }

    @Test
    fun `handle Network gives up once the retry budget is spent and tells the listener`() =
        runTest {
            val handler = makeHandler()
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            repeat(RECOVERY_ATTEMPT_BUDGET) {
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )
            }

            withClue("Budget spent on recovery, nothing surfaced yet") { errors.isEmpty() shouldBe true }

            val exhausted =
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )

            withClue("Returning false lets the caller start the idle timer") { exhausted shouldBe false }
            withClue("Listener is told once, not on every failed attempt") { errors.size shouldBe 1 }
            withClue("The message points at the manual fallback") {
                errors.single().contains("Tap play") shouldBe true
            }
            withClue("No further prepare() once the budget is spent") {
                player.prepareCount shouldBe RECOVERY_ATTEMPT_BUDGET
            }
        }

    @Test
    fun `onPlaybackHealthy refills the budget so it is per-incident, not per-session`() =
        runTest {
            val handler = makeHandler()
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            repeat(RECOVERY_ATTEMPT_BUDGET + 1) {
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )
            }
            withClue("Precondition: budget is spent") { errors.size shouldBe 1 }

            handler.onPlaybackHealthy()

            val afterRecovery =
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )

            withClue("A later, unrelated stall gets a full budget of its own") {
                afterRecovery shouldBe true
            }
            withClue("prepare() runs again after the refill") {
                player.prepareCount shouldBe RECOVERY_ATTEMPT_BUDGET + 1
            }
        }

    @Test
    fun `handle Stuck draws on the same budget as Network`() =
        runTest {
            val handler = makeHandler()
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            // A book that stalls, recovers, and stalls again is failing repeatedly whatever the
            // reported cause — so a mix of the two must not double the effective budget.
            repeat(RECOVERY_ATTEMPT_BUDGET) {
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )
            }

            val stuckAfterNetworkBudget =
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.Stuck("stuck"),
                    player = player,
                    currentBookId = BookId("book1"),
                    bookPositionMs = 1_000L,
                    onShowError = { errors += it },
                )

            withClue("Stuck shares the budget rather than getting a fresh one") {
                stuckAfterNetworkBudget shouldBe false
            }
            withClue("No stop/prepare cycle once the budget is spent") { player.stopCount shouldBe 0 }
        }

    // ── handle — NotFound ─────────────────────────────────────────────────────

    @Test
    fun `handle NotFound returns false, pauses player, shows user error`() {
        runBlocking {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 4_500L)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.NotFound("404"),
                    player = player,
                    currentBookId = BookId("book2"),
                    // Book-relative, distinct from the file-relative player position (4_500).
                    bookPositionMs = 5_400_000L,
                    onShowError = { errors += it },
                )

            withClue("NotFound should return false (no further playback)") { result shouldBe false }
            withClue("Player paused") { player.pauseCount shouldBe 1 }
            errors.size shouldBe 1
            withClue("Error message is non-blank") { errors.first().isNotBlank() shouldBe true }
            tracker.savePlaybackStateCalls.size shouldBe 1
            tracker.savePlaybackStateCalls.first().first shouldBe BookId("book2")
            withClue("Saved position must be the BOOK-relative value, not player.currentPosition (4_500)") {
                val saved = tracker.savePlaybackStateCalls.first().second
                saved.shouldBeInstanceOf<PlaybackUpdate.PeriodicUpdate>().positionMs shouldBe 5_400_000L
            }
        }
    }

    // ── handle — Codec ────────────────────────────────────────────────────────

    @Test
    fun `handle Codec returns false, pauses player, shows user error`() {
        runBlocking {
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            val result =
                makeHandler().handle(
                    error = PlaybackErrorHandler.ClassifiedError.Codec("codec"),
                    player = player,
                    currentBookId = null,
                    bookPositionMs = 0L,
                    onShowError = { errors += it },
                )

            result shouldBe false
            player.pauseCount shouldBe 1
            errors.size shouldBe 1
        }
    }

    // ── handle — AuthExpired ──────────────────────────────────────────────────
    //
    // Regression cover for a loop that could never end. On a failed refresh the provider falls
    // back to whatever is stored — re-caching the very token the server just rejected — so a
    // null check could never fire. The handler re-prepared the player against that dead token,
    // 401'd again, and repeated: no retry budget, and no error ever reaching the listener.

    @Test
    fun `handle AuthExpired gives up when the refresh re-caches the token that just failed`() =
        runTest {
            val tokens = FakeAudioTokenRecovery(initialToken = "rejected-token")
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tokens = tokens).handle(
                    error = PlaybackErrorHandler.ClassifiedError.AuthExpired("401"),
                    player = player,
                    currentBookId = BookId("book5"),
                    bookPositionMs = 7_000L,
                    onShowError = { errors += it },
                )

            withClue("A refresh that changed nothing cannot fix a 401 — retrying is an infinite loop") {
                result shouldBe false
            }
            withClue("Re-preparing against the rejected token just re-issues the same 401") {
                player.prepareCount shouldBe 0
            }
            withClue("The listener must be told to sign in, not left in silence") { errors.size shouldBe 1 }
            withClue("Playback is paused rather than left spinning") { player.pauseCount shouldBe 1 }
        }

    @Test
    fun `handle AuthExpired retries when the refresh yields a genuinely new token`() =
        runTest {
            val tokens = FakeAudioTokenRecovery(initialToken = "rejected-token") { "fresh-token" }
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tokens = tokens).handle(
                    error = PlaybackErrorHandler.ClassifiedError.AuthExpired("401"),
                    player = player,
                    currentBookId = BookId("book5"),
                    bookPositionMs = 7_000L,
                    onShowError = { errors += it },
                )

            withClue("A new credential is worth one retry") { result shouldBe true }
            withClue("prepare() is what re-issues the request with the new token") { player.prepareCount shouldBe 1 }
            player.playCount shouldBe 1
            withClue("Nothing to surface while recovery is under way") { errors.isEmpty() shouldBe true }
            withClue("The token is read AFTER the refresh completes — a fire-and-forget refresh reads the stale one") {
                tokens.refreshCount shouldBe 1
            }
        }

    @Test
    fun `handle AuthExpired stops retrying once the recovery budget is spent`() =
        runTest {
            // A server handing out fresh tokens that are still rejected would otherwise retry
            // forever: the token-comparison guard never fires, so only a budget bounds it.
            val tokens = FakeAudioTokenRecovery(initialToken = "t0") { attempt -> "t$attempt" }
            val handler = makeHandler(tokens = tokens)
            val player = FakeExoPlayer()
            val errors = mutableListOf<String>()

            repeat(RECOVERY_ATTEMPT_BUDGET) {
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.AuthExpired("401"),
                    player = player,
                    currentBookId = BookId("book5"),
                    bookPositionMs = 7_000L,
                    onShowError = { errors += it },
                )
            }
            withClue("Budget spent on recovery, nothing surfaced yet") { errors.isEmpty() shouldBe true }

            val exhausted =
                handler.handle(
                    error = PlaybackErrorHandler.ClassifiedError.AuthExpired("401"),
                    player = player,
                    currentBookId = BookId("book5"),
                    bookPositionMs = 7_000L,
                    onShowError = { errors += it },
                )

            withClue("The budget has to bound the auth path too") { exhausted shouldBe false }
            withClue("Listener is told once, not on every failed attempt") { errors.size shouldBe 1 }
            withClue("No further prepare() once the budget is spent") {
                player.prepareCount shouldBe RECOVERY_ATTEMPT_BUDGET
            }
        }

    // ── handle — Stuck ────────────────────────────────────────────────────────

    @Test
    fun `handle Stuck returns true and performs stop-prepare-seekTo-play recovery`() {
        runTest {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 12_000L, stubbedMediaItemIndex = 2)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.Stuck("stuck"),
                    player = player,
                    currentBookId = BookId("book3"),
                    // Book-relative save value; the Stuck recovery seek still uses the
                    // FILE-relative player coordinates (index 2, 12_000) — that is correct,
                    // it re-seeks within the same media item.
                    bookPositionMs = 3_612_000L,
                    onShowError = { errors += it },
                )

            withClue("Stuck returns true (recovery attempted)") { result shouldBe true }
            withClue("stop() called once") { player.stopCount shouldBe 1 }
            withClue("prepare() called once") { player.prepareCount shouldBe 1 }
            withClue("play() called once") { player.playCount shouldBe 1 }
            withClue("seekTo called once (position > 0)") { player.seekCalls.size shouldBe 1 }
            withClue("seekTo uses saved mediaItemIndex") { player.seekCalls.first().first shouldBe 2 }
            withClue("seekTo uses FILE-relative player position for in-item recovery") {
                player.seekCalls.first().second shouldBe 12_000L
            }
            withClue("Saved position is the BOOK-relative value, not player.currentPosition (12_000)") {
                val saved = tracker.savePlaybackStateCalls.first().second
                saved.shouldBeInstanceOf<PlaybackUpdate.PeriodicUpdate>().positionMs shouldBe 3_612_000L
            }
            withClue("No user error shown for stuck recovery attempt") { errors.isEmpty() shouldBe true }
            tracker.savePlaybackStateCalls.size shouldBe 1
        }
    }

    @Test
    fun `handle Stuck at position 0 does not call seekTo`() {
        runTest {
            val player = FakeExoPlayer(stubbedPosition = 0L, stubbedMediaItemIndex = 0)

            makeHandler().handle(
                error = PlaybackErrorHandler.ClassifiedError.Stuck("stuck at zero"),
                player = player,
                currentBookId = null,
                bookPositionMs = 0L,
                onShowError = {},
            )

            withClue("seekTo NOT called when position is 0") { player.seekCalls.size shouldBe 0 }
        }
    }

    // ── handle — Unknown ──────────────────────────────────────────────────────

    @Test
    fun `handle Unknown returns false, pauses player, shows user error`() {
        runBlocking {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 3_000L)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.Unknown(RuntimeException("unexpected")),
                    player = player,
                    currentBookId = BookId("book4"),
                    bookPositionMs = 3_003_000L,
                    onShowError = { errors += it },
                )

            result shouldBe false
            player.pauseCount shouldBe 1
            errors.size shouldBe 1
            tracker.savePlaybackStateCalls.size shouldBe 1
            tracker.savePlaybackStateCalls.first().first shouldBe BookId("book4")
        }
    }

    // ── getErrorMessage ────────────────────────────────────────────────────────

    @Test
    fun `getErrorMessage returns distinct non-blank strings for all error types`() {
        val handler = makeHandler()
        val messages =
            listOf(
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.Network("n")),
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.AuthExpired("a")),
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.NotFound("nf")),
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.Codec("c")),
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.Stuck("s")),
                handler.getErrorMessage(PlaybackErrorHandler.ClassifiedError.Unknown(RuntimeException())),
            )

        withClue("All messages must be non-blank") { messages.all { it.isNotBlank() } shouldBe true }
        withClue("Each error type must have a distinct message") { messages.distinct().size shouldBe messages.size }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Constructs a [PlaybackErrorHandler] backed by the given [tracker] and [tokens]
     * (fresh defaults when omitted).
     *
     * The default [FakeAudioTokenRecovery] reproduces the production failure mode — a refresh
     * that leaves the cached token untouched — so any test that reaches the AuthExpired branch
     * without opting in sees the hostile case rather than a convenient one.
     */
    private fun makeHandler(
        tracker: FakeProgressTracker = FakeProgressTracker(),
        tokens: AudioTokenRecovery = FakeAudioTokenRecovery(),
    ): PlaybackErrorHandler =
        PlaybackErrorHandler(
            progressTracker = tracker.tracker,
            tokenProvider = tokens,
        )

    /**
     * Builds a [PlaybackException] with error code [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS]
     * whose cause is an [HttpDataSource.InvalidResponseCodeException] carrying [responseCode].
     *
     * [DataSpec] requires [android.net.Uri], so this helper only works under Robolectric.
     */
    private fun makeHttpStatusException(responseCode: Int): PlaybackException {
        val dataSpec = DataSpec(Uri.parse("https://test.example.com/audio/chunk.mp3"))
        val httpException =
            HttpDataSource.InvalidResponseCodeException(
                responseCode,
                "HTTP $responseCode",
                null,
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )
        return PlaybackException(
            "HTTP $responseCode",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

/**
 * [AudioTokenRecovery] whose refresh outcome the test dictates.
 *
 * [onRefresh] maps the refresh attempt number to the token that refresh installs; returning
 * `null` means "the refresh changed nothing", which is what the real provider does when the
 * server rejects the refresh and it falls back to the stored token. That is the default, because
 * it is the case that produced the loop.
 *
 * The token only ever changes *inside* [refresh], so a caller that reads [currentToken] without
 * awaiting the refresh observes the stale value — which is what makes the awaiting test honest.
 */
private class FakeAudioTokenRecovery(
    initialToken: String? = "rejected-token",
    private val onRefresh: (attempt: Int) -> String? = { null },
) : AudioTokenRecovery {
    private var token: String? = initialToken

    /** How many times [refresh] has been awaited to completion. */
    var refreshCount = 0
        private set

    override fun currentToken(): String? = token

    override suspend fun refresh() {
        refreshCount++
        onRefresh(refreshCount)?.let { token = it }
    }
}

/**
 * Minimal [ProgressTracker] that records position-save calls for handler tests.
 *
 * [savePositionNow] is not `open` (intentionally, to avoid `:shared` changes).
 * We therefore track calls at the [PlaybackPositionRepository.savePlaybackState] seam
 * instead. [savePlaybackStateCalls] records every `(bookId, update)` pair passed through.
 *
 * The remaining stub repositories throw on any call — none should be reached in
 * [PlaybackErrorHandler] tests because those code paths are not exercised.
 */
private class FakeProgressTracker {
    private val repo = RecordingPositionRepository()

    /** All `savePlaybackState` calls routed through [savePositionNow], keyed by bookId. */
    val savePlaybackStateCalls: List<Pair<BookId, PlaybackUpdate>>
        get() = repo.calls

    val tracker: ProgressTracker =
        object : ProgressTracker(
            downloadRepository = ThrowingDownloadRepository,
            positionRepository = repo,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ) {}
}

/**
 * [PlaybackPositionRepository] that records [savePlaybackState] calls and returns
 * [AppResult.Success] so [savePositionNow] completes without error.
 */
private class RecordingPositionRepository : PlaybackPositionRepository {
    private val _calls = mutableListOf<Pair<BookId, PlaybackUpdate>>()
    val calls: List<Pair<BookId, PlaybackUpdate>> get() = _calls.toList()

    override suspend fun savePlaybackState(
        bookId: BookId,
        update: PlaybackUpdate,
    ): AppResult<Unit> {
        _calls += bookId to update
        return AppResult.Success(Unit)
    }

    override suspend fun get(bookId: BookId) = TODO("not used")

    override fun observeAll() = TODO("not used")

    override fun observe(bookId: BookId) = TODO("not used")

    override suspend fun delete(bookId: BookId) = TODO("not used")

    override suspend fun markComplete(
        bookId: BookId,
        startedAt: Long?,
        finishedAt: Long?,
    ) = TODO("not used")

    override suspend fun discardProgress(bookId: BookId) = TODO("not used")

    override suspend fun restartBook(bookId: BookId) = TODO("not used")

    override suspend fun getLastPlayedBook() = TODO("not used")
}

private object ThrowingDownloadRepository : DownloadRepository {
    override fun observeForBook(bookId: BookId) = TODO("not used in handler test")

    override fun observeAll() = TODO("not used in handler test")

    override fun observeBookStatus(bookId: BookId) = TODO("not used in handler test")

    override fun observeAllStatuses() = TODO("not used in handler test")

    override fun observeDownloadedBooks() = TODO("not used in handler test")

    override suspend fun getLocalPath(audioFileId: String): String? = TODO("not used in handler test")

    override suspend fun getStateForAudioFile(audioFileId: String) = TODO("not used in handler test")

    override suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ) = TODO("not used in handler test")

    override suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ) = TODO("not used in handler test")

    override suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ) = TODO("not used in handler test")

    override suspend fun markPaused(audioFileId: String) = TODO("not used in handler test")

    override suspend fun markCancelled(audioFileId: String) = TODO("not used in handler test")

    override suspend fun markFailed(
        audioFileId: String,
        error: com.calypsan.listenup.api.error.DownloadError,
    ) = TODO("not used in handler test")

    override suspend fun enqueueForBook(bookId: BookId) = TODO("not used in handler test")

    override suspend fun cancelForBook(bookId: BookId) = TODO("not used in handler test")

    override suspend fun deleteForBook(bookId: String) = TODO("not used in handler test")

    override suspend fun deleteDeletedRecordsForBook(bookId: String) = TODO("not used in handler test")

    override suspend fun resumeIncompleteDownloads() = TODO("not used in handler test")
}

// ── Stubs ─────────────────────────────────────────────────────────────────────

/**
 * Hand-written [ExoPlayer] fake that records the calls made against it.
 * All other methods are no-ops or return sensible defaults.
 *
 * `internal` rather than file-private so [ListenUpSessionCallbackTest] can drive the
 * session callback's transport seam against it too. Implementing ExoPlayer's full surface
 * costs ~400 lines, so one fake serves both suites rather than each growing its own.
 */
@OptIn(UnstableApi::class)
internal class FakeExoPlayer(
    stubbedPosition: Long = 5_000L,
    stubbedMediaItemIndex: Int = 0,
    private val stubbedDuration: Long = 0L,
    // Opt-in, because MediaSession.Builder rejects a player that answers `false` here.
    // Defaults to false to keep the fake honest about what it is — only a fake standing in
    // as a session's player needs to claim otherwise.
    private val canAdvertiseSession: Boolean = false,
    // Opt-in for the same reason: SimpleBasePlayer drops any command its state does not
    // advertise, so a fake wrapped by ChapterWindowPlayer must declare the seek commands it
    // wants exercised or handleSeek is never reached (see ChapterWindowPlayerSeekTest).
    //
    // Null rather than Player.Commands.EMPTY so constructing the fake does not, by itself,
    // force Player.Commands' static initializer. As of Media3 1.11 that initializer builds
    // through SparseBooleanArray, which throws under the plain android.jar stubs — that would
    // confine every user of this fake to Robolectric, including suites like AutoRewindSeekTest
    // whose subject is pure logic and never reads the command set.
    private val availableCommands: Player.Commands? = null,
) : ExoPlayer {
    private val _currentPosition = stubbedPosition
    private val _currentMediaItemIndex = stubbedMediaItemIndex
    var pauseCount = 0
    var playCount = 0
    var stopCount = 0
    var prepareCount = 0

    /** `seekTo(mediaItemIndex, positionMs)` calls — the book-relative, timeline-resolved path. */
    val seekCalls = mutableListOf<Pair<Int, Long>>()

    /**
     * `seekTo(positionMs)` calls — the single-argument overload, which seeks
     * FILE-relatively within the current item. Recorded separately from [seekCalls]
     * because conflating the two coordinate spaces is the #1241 bug class: a test that
     * could not tell them apart would pass on exactly the defect it exists to catch.
     */
    val fileRelativeSeekCalls = mutableListOf<Long>()

    // ── Methods called by PlaybackErrorHandler.handle ──────────────────────────
    override fun pause() {
        pauseCount++
    }

    override fun play() {
        playCount++
    }

    override fun stop() {
        stopCount++
    }

    override fun prepare() {
        prepareCount++
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        seekCalls += mediaItemIndex to positionMs
    }

    override fun getCurrentPosition(): Long = _currentPosition

    override fun getCurrentMediaItemIndex(): Int = _currentMediaItemIndex

    // ── Player abstract methods — stubs ───────────────────────────────────────
    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun addListener(listener: Player.Listener) = Unit

    override fun removeListener(listener: Player.Listener) = Unit

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        resetPosition: Boolean,
    ) = Unit

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = Unit

    override fun setMediaItem(mediaItem: MediaItem) = Unit

    override fun setMediaItem(
        mediaItem: MediaItem,
        startPositionMs: Long,
    ) = Unit

    override fun setMediaItem(
        mediaItem: MediaItem,
        resetPosition: Boolean,
    ) = Unit

    override fun addMediaItem(mediaItem: MediaItem) = Unit

    override fun addMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) = Unit

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) = Unit

    override fun addMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ) = Unit

    override fun moveMediaItem(
        currentIndex: Int,
        newIndex: Int,
    ) = Unit

    override fun moveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ) = Unit

    override fun replaceMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) = Unit

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) = Unit

    override fun removeMediaItem(index: Int) = Unit

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) = Unit

    override fun clearMediaItems() = Unit

    override fun isCommandAvailable(command: Int): Boolean = false

    override fun canAdvertiseSession(): Boolean = canAdvertiseSession

    override fun getAvailableCommands(): Player.Commands = availableCommands ?: Player.Commands.EMPTY

    override fun getPlaybackState(): Int = Player.STATE_IDLE

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun isPlaying(): Boolean = false

    override fun getPlayerError(): ExoPlaybackException? = null

    override fun setPlayWhenReady(playWhenReady: Boolean) = Unit

    override fun getPlayWhenReady(): Boolean = false

    override fun setRepeatMode(repeatMode: Int) = Unit

    override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = false

    override fun seekToDefaultPosition() = Unit

    override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit

    override fun seekTo(positionMs: Long) {
        fileRelativeSeekCalls += positionMs
    }

    override fun getSeekBackIncrement(): Long = 0L

    override fun seekBack() = Unit

    override fun getSeekForwardIncrement(): Long = 0L

    override fun seekForward() = Unit

    override fun hasPreviousMediaItem(): Boolean = false

    override fun seekToPreviousMediaItem() = Unit

    override fun getMaxSeekToPreviousPosition(): Long = 0L

    override fun seekToPrevious() = Unit

    override fun hasNextMediaItem(): Boolean = false

    override fun seekToNextMediaItem() = Unit

    override fun seekToNext() = Unit

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun release() = Unit

    override fun getCurrentTracks(): Tracks = Tracks.EMPTY

    override fun getTrackSelectionParameters(): TrackSelectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) = Unit

    override fun getMediaMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = Unit

    override fun getCurrentManifest(): Any? = null

    override fun getCurrentTimeline(): Timeline = Timeline.EMPTY

    override fun getCurrentPeriodIndex(): Int = 0

    override fun getCurrentWindowIndex(): Int = 0

    override fun getNextWindowIndex(): Int = -1

    override fun getNextMediaItemIndex(): Int = -1

    override fun getPreviousWindowIndex(): Int = -1

    override fun getPreviousMediaItemIndex(): Int = -1

    override fun getCurrentMediaItem(): MediaItem? = null

    override fun getMediaItemCount(): Int = 0

    override fun getMediaItemAt(index: Int): MediaItem = MediaItem.EMPTY

    override fun getDuration(): Long = stubbedDuration

    override fun getBufferedPosition(): Long = 0L

    override fun getBufferedPercentage(): Int = 0

    override fun getTotalBufferedDuration(): Long = 0L

    override fun isCurrentWindowDynamic(): Boolean = false

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun isCurrentWindowLive(): Boolean = false

    override fun isCurrentMediaItemLive(): Boolean = false

    override fun getCurrentLiveOffset(): Long = 0L

    override fun isCurrentWindowSeekable(): Boolean = false

    override fun isCurrentMediaItemSeekable(): Boolean = false

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = -1

    override fun getCurrentAdIndexInAdGroup(): Int = -1

    override fun getContentDuration(): Long = 0L

    override fun getContentPosition(): Long = _currentPosition

    override fun getContentBufferedPosition(): Long = 0L

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setVolume(volume: Float) = Unit

    override fun getVolume(): Float = 1.0f

    override fun mute() = Unit

    override fun unmute() = Unit

    override fun clearVideoSurface() = Unit

    override fun clearVideoSurface(surface: Surface?) = Unit

    override fun setVideoSurface(surface: Surface?) = Unit

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = Unit

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = Unit

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) = Unit

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) = Unit

    override fun setVideoTextureView(textureView: TextureView?) = Unit

    override fun clearVideoTextureView(textureView: TextureView?) = Unit

    override fun getVideoSize(): VideoSize = VideoSize.UNKNOWN

    override fun getSurfaceSize(): Size = Size.UNKNOWN

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN

    override fun getDeviceVolume(): Int = 0

    override fun isDeviceMuted(): Boolean = false

    override fun setDeviceVolume(volume: Int) = Unit

    override fun setDeviceVolume(
        volume: Int,
        flags: Int,
    ) = Unit

    override fun increaseDeviceVolume() = Unit

    override fun increaseDeviceVolume(flags: Int) = Unit

    override fun decreaseDeviceVolume() = Unit

    override fun decreaseDeviceVolume(flags: Int) = Unit

    override fun setDeviceMuted(muted: Boolean) = Unit

    override fun setDeviceMuted(
        muted: Boolean,
        flags: Int,
    ) = Unit

    override fun setAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ) = Unit

    // ── ExoPlayer abstract methods — stubs ────────────────────────────────────
    override fun addAudioOffloadListener(listener: ExoPlayer.AudioOffloadListener) = Unit

    override fun removeAudioOffloadListener(listener: ExoPlayer.AudioOffloadListener) = Unit

    override fun getAnalyticsCollector(): AnalyticsCollector = error("not stubbed")

    override fun addAnalyticsListener(listener: AnalyticsListener) = Unit

    override fun removeAnalyticsListener(listener: AnalyticsListener) = Unit

    override fun getRendererCount(): Int = 0

    override fun getRendererType(index: Int): Int = 0

    override fun getRenderer(index: Int): Renderer = error("not stubbed")

    override fun getSecondaryRenderer(index: Int): Renderer = error("not stubbed")

    override fun getTrackSelector(): TrackSelector? = null

    override fun getCurrentTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

    override fun getCurrentTrackSelections(): TrackSelectionArray = TrackSelectionArray()

    override fun getPlaybackLooper(): Looper = Looper.getMainLooper()

    override fun getClock(): Clock = Clock.DEFAULT

    override fun prepare(mediaSource: MediaSource) = Unit

    override fun prepare(
        mediaSource: MediaSource,
        resetPosition: Boolean,
        resetState: Boolean,
    ) = Unit

    override fun setMediaSources(mediaSources: MutableList<MediaSource>) = Unit

    override fun setMediaSources(
        mediaSources: MutableList<MediaSource>,
        resetPosition: Boolean,
    ) = Unit

    override fun setMediaSources(
        mediaSources: MutableList<MediaSource>,
        startMediaItemIndex: Int,
        startPositionMs: Long,
    ) = Unit

    override fun setMediaSource(mediaSource: MediaSource) = Unit

    override fun setMediaSource(
        mediaSource: MediaSource,
        startPositionMs: Long,
    ) = Unit

    override fun setMediaSource(
        mediaSource: MediaSource,
        resetPosition: Boolean,
    ) = Unit

    override fun addMediaSource(mediaSource: MediaSource) = Unit

    override fun addMediaSource(
        index: Int,
        mediaSource: MediaSource,
    ) = Unit

    override fun addMediaSources(mediaSources: MutableList<MediaSource>) = Unit

    override fun addMediaSources(
        index: Int,
        mediaSources: MutableList<MediaSource>,
    ) = Unit

    override fun setShuffleOrder(shuffleOrder: ShuffleOrder) = Unit

    override fun getShuffleOrder(): ShuffleOrder = error("not stubbed")

    override fun setPreloadConfiguration(preloadConfiguration: ExoPlayer.PreloadConfiguration) = Unit

    override fun getPreloadConfiguration(): ExoPlayer.PreloadConfiguration = ExoPlayer.PreloadConfiguration.DEFAULT

    override fun setAudioSessionId(audioSessionId: Int) = Unit

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    override fun clearAuxEffectInfo() = Unit

    override fun setPreferredAudioDevice(audioDeviceInfo: AudioDeviceInfo?) = Unit

    override fun setVirtualDeviceId(virtualDeviceId: Int) = Unit

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setScrubbingModeEnabled(scrubbingModeEnabled: Boolean) = Unit

    override fun isScrubbingModeEnabled(): Boolean = false

    override fun setScrubbingModeParameters(scrubbingModeParameters: ScrubbingModeParameters) = Unit

    override fun getScrubbingModeParameters(): ScrubbingModeParameters = ScrubbingModeParameters.DEFAULT

    override fun setVideoEffects(videoEffects: MutableList<Effect>) = Unit

    override fun setVideoScalingMode(videoScalingMode: Int) = Unit

    override fun getVideoScalingMode(): Int = 0

    override fun setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy: Int) = Unit

    override fun getVideoChangeFrameRateStrategy(): Int = 0

    override fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener) = Unit

    override fun clearVideoFrameMetadataListener(listener: VideoFrameMetadataListener) = Unit

    override fun setCameraMotionListener(listener: CameraMotionListener) = Unit

    override fun clearCameraMotionListener(listener: CameraMotionListener) = Unit

    override fun createMessage(target: PlayerMessage.Target): PlayerMessage = error("not stubbed")

    override fun setSeekParameters(seekParameters: SeekParameters?) = Unit

    override fun getSeekParameters(): SeekParameters = SeekParameters.DEFAULT

    override fun setSeekBackIncrementMs(seekBackIncrementMs: Long) = Unit

    override fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) = Unit

    override fun setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs: Long) = Unit

    override fun setForegroundMode(foregroundMode: Boolean) = Unit

    override fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean) = Unit

    override fun getPauseAtEndOfMediaItems(): Boolean = false

    // Media3 1.11 added this to ExoPlayer. Audiobooks carry no ads, so the fake
    // records nothing — it exists only to satisfy the interface.
    override fun setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh: Boolean) = Unit

    override fun getAudioFormat(): Format? = null

    override fun getVideoFormat(): Format? = null

    override fun getAudioDecoderCounters(): DecoderCounters? = null

    override fun getVideoDecoderCounters(): DecoderCounters? = null

    override fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean) = Unit

    override fun setWakeMode(wakeMode: Int) = Unit

    override fun setPriority(priority: Int) = Unit

    override fun setPriorityTaskManager(priorityTaskManager: PriorityTaskManager?) = Unit

    override fun isSleepingForOffload(): Boolean = false

    override fun isTunnelingEnabled(): Boolean = false

    override fun isReleased(): Boolean = false

    override fun setImageOutput(imageOutput: ImageOutput?) = Unit

    override fun setAudioCodecParameters(codecParameters: CodecParameters) = Unit

    override fun addAudioCodecParametersChangeListener(
        listener: CodecParametersChangeListener,
        supportedMimeTypes: MutableList<String>,
    ) = Unit

    override fun removeAudioCodecParametersChangeListener(listener: CodecParametersChangeListener) = Unit

    override fun setVideoCodecParameters(codecParameters: CodecParameters) = Unit

    override fun addVideoCodecParametersChangeListener(
        listener: CodecParametersChangeListener,
        supportedMimeTypes: MutableList<String>,
    ) = Unit

    override fun removeVideoCodecParametersChangeListener(listener: CodecParametersChangeListener) = Unit
}
