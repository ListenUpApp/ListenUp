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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
 * - `handle()` — decision logic and return values for Network, NotFound, Codec, Stuck, Unknown,
 *   including position-save verification and player interaction recording.
 *
 * Note on AuthExpired in `handle()`: the AuthExpired branch calls
 * [AndroidAudioTokenProvider.onUnauthorized] and [AndroidAudioTokenProvider.getToken], which
 * delegate to [CachedAudioTokenProvider] methods that are not `open` and therefore cannot be
 * overridden in a test subclass. Building a full [CachedAudioTokenProvider] with fakes is
 * non-deterministic because the constructor launches a background proactive-refresh coroutine.
 * The `classify` tests for 401/403 confirm correct classification; the recovery path is noted
 * as a gap in the coverage report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
        runBlocking {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 8_000L)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                    player = player,
                    currentBookId = BookId("book1"),
                    onShowError = { errors += it },
                )

            withClue("Network error should return true (ExoPlayer handles retry)") { result shouldBe true }
            withClue("Player should NOT be paused for network errors") { player.pauseCount shouldBe 0 }
            withClue("No user-visible error for transient network issues") { errors.isEmpty() shouldBe true }
            withClue("Position saved once before handling") { tracker.savePlaybackStateCalls.size shouldBe 1 }
            tracker.savePlaybackStateCalls.first().first shouldBe BookId("book1")
        }
    }

    @Test
    fun `handle Network skips position save when currentBookId is null`() {
        runBlocking {
            val tracker = FakeProgressTracker()

            makeHandler(tracker = tracker).handle(
                error = PlaybackErrorHandler.ClassifiedError.Network("net"),
                player = FakeExoPlayer(),
                currentBookId = null,
                onShowError = {},
            )

            withClue("No save when bookId is null") { tracker.savePlaybackStateCalls.size shouldBe 0 }
        }
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
                    onShowError = { errors += it },
                )

            withClue("NotFound should return false (no further playback)") { result shouldBe false }
            withClue("Player paused") { player.pauseCount shouldBe 1 }
            errors.size shouldBe 1
            withClue("Error message is non-blank") { errors.first().isNotBlank() shouldBe true }
            tracker.savePlaybackStateCalls.size shouldBe 1
            tracker.savePlaybackStateCalls.first().first shouldBe BookId("book2")
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
                    onShowError = { errors += it },
                )

            result shouldBe false
            player.pauseCount shouldBe 1
            errors.size shouldBe 1
        }
    }

    // ── handle — Stuck ────────────────────────────────────────────────────────

    @Test
    fun `handle Stuck returns true and performs stop-prepare-seekTo-play recovery`() {
        runBlocking {
            val tracker = FakeProgressTracker()
            val player = FakeExoPlayer(stubbedPosition = 12_000L, stubbedMediaItemIndex = 2)
            val errors = mutableListOf<String>()

            val result =
                makeHandler(tracker = tracker).handle(
                    error = PlaybackErrorHandler.ClassifiedError.Stuck("stuck"),
                    player = player,
                    currentBookId = BookId("book3"),
                    onShowError = { errors += it },
                )

            withClue("Stuck returns true (recovery attempted)") { result shouldBe true }
            withClue("stop() called once") { player.stopCount shouldBe 1 }
            withClue("prepare() called once") { player.prepareCount shouldBe 1 }
            withClue("play() called once") { player.playCount shouldBe 1 }
            withClue("seekTo called once (position > 0)") { player.seekCalls.size shouldBe 1 }
            withClue("seekTo uses saved mediaItemIndex") { player.seekCalls.first().first shouldBe 2 }
            withClue("seekTo uses saved position") { player.seekCalls.first().second shouldBe 12_000L }
            withClue("No user error shown for stuck recovery attempt") { errors.isEmpty() shouldBe true }
            tracker.savePlaybackStateCalls.size shouldBe 1
        }
    }

    @Test
    fun `handle Stuck at position 0 does not call seekTo`() {
        runBlocking {
            val player = FakeExoPlayer(stubbedPosition = 0L, stubbedMediaItemIndex = 0)

            makeHandler().handle(
                error = PlaybackErrorHandler.ClassifiedError.Stuck("stuck at zero"),
                player = player,
                currentBookId = null,
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
     * Constructs a [PlaybackErrorHandler] backed by the given [tracker] (or a fresh default)
     * alongside a minimal [AndroidAudioTokenProvider] wired from stub dependencies.
     *
     * The [AndroidAudioTokenProvider] is only exercised in the AuthExpired branch of
     * [PlaybackErrorHandler.handle], which is not covered here — see class-level KDoc.
     */
    private fun makeHandler(tracker: FakeProgressTracker = FakeProgressTracker()): PlaybackErrorHandler {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val core = CachedAudioTokenProvider(StubAuthSession(), StubAuthRepository(), scope)
        return PlaybackErrorHandler(
            progressTracker = tracker.tracker,
            tokenProvider = AndroidAudioTokenProvider(core),
        )
    }

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

    override suspend fun resumeIncompleteDownloads() = TODO("not used in handler test")
}

// ── Stubs ─────────────────────────────────────────────────────────────────────

/**
 * [ExoPlayer] stub that records the calls made by [PlaybackErrorHandler.handle].
 * All other methods are no-ops or return sensible defaults.
 */
@OptIn(UnstableApi::class)
private class FakeExoPlayer(
    stubbedPosition: Long = 5_000L,
    stubbedMediaItemIndex: Int = 0,
) : ExoPlayer {
    private val _currentPosition = stubbedPosition
    private val _currentMediaItemIndex = stubbedMediaItemIndex
    var pauseCount = 0
    var playCount = 0
    var stopCount = 0
    var prepareCount = 0
    val seekCalls = mutableListOf<Pair<Int, Long>>()

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

    override fun canAdvertiseSession(): Boolean = false

    override fun getAvailableCommands(): Player.Commands = Player.Commands.EMPTY

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

    override fun seekTo(positionMs: Long) = Unit

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

    override fun getDuration(): Long = 0L

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

// ── Auth stubs for makeHandler() ──────────────────────────────────────────────

private class StubAuthSession : com.calypsan.listenup.client.domain.repository.AuthSession {
    override val authState: StateFlow<com.calypsan.listenup.client.domain.model.AuthState> =
        MutableStateFlow(
            com.calypsan.listenup.client.domain.model.AuthState.Authenticated(
                com.calypsan.listenup.api.dto.auth
                    .UserId("u1"),
                com.calypsan.listenup.api.dto.auth
                    .SessionId("s1"),
            ),
        )

    override suspend fun getAccessToken() =
        com.calypsan.listenup.api.dto.auth
            .AccessToken("stub")

    override suspend fun getRefreshToken() =
        com.calypsan.listenup.api.dto.auth
            .RefreshToken("stub-r")

    override suspend fun getSessionId(): String? = "s1"

    override suspend fun getUserId(): String? = "u1"

    override suspend fun saveAuthTokens(
        access: com.calypsan.listenup.api.dto.auth.AccessToken,
        refresh: com.calypsan.listenup.api.dto.auth.RefreshToken,
        sessionId: String,
        userId: String,
    ) = Unit

    override suspend fun updateAccessToken(token: com.calypsan.listenup.api.dto.auth.AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

    override suspend fun isAuthenticated() = true

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus() =
        com.calypsan.listenup.client.domain.model.AuthState.Authenticated(
            com.calypsan.listenup.api.dto.auth
                .UserId("u1"),
            com.calypsan.listenup.api.dto.auth
                .SessionId("s1"),
        )

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): com.calypsan.listenup.client.domain.repository.PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}

private class StubAuthRepository : com.calypsan.listenup.client.domain.repository.AuthRepository {
    private val rotatedSession
        get() =
            com.calypsan.listenup.api.dto.auth.AuthSession(
                accessToken =
                    com.calypsan.listenup.api.dto.auth
                        .AccessToken("rotated"),
                accessTokenExpiresAt = System.currentTimeMillis() + 60 * 60 * 1_000L,
                refreshToken =
                    com.calypsan.listenup.api.dto.auth
                        .RefreshToken("rotated-r"),
                refreshTokenExpiresAt = System.currentTimeMillis() + 60 * 60 * 1_000L,
                sessionId =
                    com.calypsan.listenup.api.dto.auth
                        .SessionId("s1"),
                user =
                    com.calypsan.listenup.api.dto.auth.User(
                        id =
                            com.calypsan.listenup.api.dto.auth
                                .UserId("u1"),
                        email = "test@example.com",
                        displayName = "Test",
                        role = com.calypsan.listenup.api.dto.auth.UserRole.MEMBER,
                        status = com.calypsan.listenup.api.dto.auth.UserStatus.ACTIVE,
                        createdAt = 0L,
                    ),
            )

    override suspend fun login(request: com.calypsan.listenup.api.dto.auth.LoginRequest) = TODO()

    override suspend fun register(request: com.calypsan.listenup.api.dto.auth.RegisterRequest) = TODO()

    override suspend fun setup(request: com.calypsan.listenup.api.dto.auth.RegisterRequest) = TODO()

    override suspend fun logout() = TODO()

    override suspend fun refreshAccessToken() =
        com.calypsan.listenup.api.result.AppResult
            .Success(rotatedSession)

    override suspend fun listSessions(): com.calypsan.listenup.api.result.AppResult<List<com.calypsan.listenup.api.dto.auth.SessionSummary>> =
        com.calypsan.listenup.api.result.AppResult
            .Success(emptyList())

    override suspend fun revokeSession(sessionId: com.calypsan.listenup.api.dto.auth.SessionId): com.calypsan.listenup.api.result.AppResult<Unit> =
        com.calypsan.listenup.api.result.AppResult
            .Success(Unit)

    override suspend fun logoutAll(): com.calypsan.listenup.api.result.AppResult<Unit> =
        com.calypsan.listenup.api.result.AppResult
            .Success(Unit)
}
