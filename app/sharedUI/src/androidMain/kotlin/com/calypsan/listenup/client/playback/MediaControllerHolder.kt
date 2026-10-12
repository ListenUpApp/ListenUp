package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.AppError
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger = KotlinLogging.logger {}

/** Bound on [MediaControllerHolder.awaitController]'s wait for a Media3 service bind. */
private const val CONTROLLER_CONNECT_TIMEOUT_MS = 10_000L

/** Media3's failure, typed once here (spec §4.6). */
internal fun PlaybackException.toPlaybackError(): AppError = playbackErrorFor(errorCode, localizedMessage)

/**
 * The typed failure for a Media3 [errorCode]. Split from [toPlaybackError] because a [PlaybackException]
 * cannot be constructed off-device without Robolectric (its constructor reads `SystemClock`).
 */
internal fun playbackErrorFor(
    errorCode: Int,
    localizedMessage: String?,
): AppError {
    val errorCodeName = PlaybackException.getErrorCodeName(errorCode)
    return when (errorCode) {
        in NETWORK_ERROR_CODES -> PlaybackError.ConnectionLost(debugInfo = errorCodeName)
        in FORMAT_ERROR_CODES -> PlaybackError.UnsupportedFormat(debugInfo = errorCodeName)
        else -> PlaybackError.CouldNotStart(debugInfo = "$errorCodeName: ${localizedMessage ?: "no message"}")
    }
}

private val NETWORK_ERROR_CODES =
    setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

private val FORMAT_ERROR_CODES =
    setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )

/**
 * Suspends until [ready] emits `true`, bounded by [timeoutMs]. Returns `true` once ready, or
 * `false` if the bound elapses first. `internal` (not `private`) so it can be tested directly
 * against a plain [kotlinx.coroutines.flow.MutableStateFlow] — [MediaController] itself has no
 * public or internal constructor and cannot be instantiated or mocked in a JVM host test, so this
 * is where the actual bounded-wait mechanism is verified.
 *
 * A genuine external cancellation of the calling coroutine always propagates — only this
 * function's own timeout is absorbed, matching [withTimeoutOrNull]'s contract.
 */
internal suspend fun awaitReady(
    ready: StateFlow<Boolean>,
    timeoutMs: Long,
): Boolean = withTimeoutOrNull(timeoutMs) { ready.first { it } } != null

/**
 * Singleton holder for the MediaController connection.
 *
 * NowPlayingViewModel consumes this controller via the PlaybackController seam
 * (there is no longer a separate PlayerViewModel). The holder owns the
 * Player.Listener that drives PlaybackStateWriter's state/speed/error updates on
 * Android — those are coordinate-agnostic and forwarded faithfully by
 * `ChapterWindowPlayer`, the chapter-scoped presentation wrapper the session now
 * hands out to controllers. Position polling used to live here too, but once the
 * session player became that wrapper, the controller's `currentMediaItemIndex`/
 * `currentPosition` turned chapter-relative — polling them here would corrupt
 * book-position tracking for any book past its first chapter. The poll now lives
 * in `PlaybackService` instead, which reads the raw transport player directly and
 * pushes book-relative coordinates through the same `PlaybackStateWriter` seam.
 *
 * The holder manages the lifecycle through reference counting:
 * - Each consumer calls acquire() on init
 * - Each consumer calls release() on onCleared()
 * - Connection is established on first acquire
 * - Connection is released when refCount hits 0
 */
@OptIn(ExperimentalAtomicApi::class)
class MediaControllerHolder(
    private val context: Context,
    private val playbackManager: PlaybackStateWriter,
    /**
     * Builds the connection future given the [MediaController.Listener] that detects a session
     * drop. Defaults to the real Media3 [MediaController.Builder]. Overridable so tests can
     * exercise connect/reconnect without a real [Context] or Media3 session — see
     * `MediaControllerHolderTest`. (Takes the listener as a parameter, rather than closing over
     * it, because a constructor default-value expression cannot reference `this` — [handleDisconnect]
     * isn't available yet at that point in initialization.)
     */
    private val connectionFactory: (MediaController.Listener) -> ListenableFuture<MediaController> = { listener ->
        MediaController
            .Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).setListener(listener)
            .buildAsync()
    },
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var connectedController: MediaController? = null

    /** Detects a Media3-initiated session drop and reconnects via [handleDisconnect]. */
    private val disconnectListener =
        object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) = handleDisconnect()
        }

    val connected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val refCount = AtomicInt(0)

    /**
     * Get the current MediaController, or null if not connected.
     */
    val controller: MediaController?
        get() = connectedController

    internal val playerListener: Player.Listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playbackManager.setPlaying(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackManager.setBuffering(playbackState == Player.STATE_BUFFERING)
                playbackManager.setPlaybackState(toCommonPlaybackState(playbackState))
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackManager.reportError(error.toPlaybackError())
                playbackManager.setPlaying(false)
                logger.error { "ExoPlayer error: ${error.errorCodeName} - ${error.message ?: "no message"}" }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                playbackManager.updateSpeed(playbackParameters.speed)
            }
        }

    private fun toCommonPlaybackState(media3State: Int): PlaybackState =
        when (media3State) {
            Player.STATE_IDLE -> {
                PlaybackState.Idle
            }

            Player.STATE_BUFFERING -> {
                PlaybackState.Buffering
            }

            Player.STATE_READY -> {
                if (connectedController?.isPlaying ==
                    true
                ) {
                    PlaybackState.Playing
                } else {
                    PlaybackState.Paused
                }
            }

            Player.STATE_ENDED -> {
                PlaybackState.Ended
            }

            else -> {
                PlaybackState.Idle
            }
        }

    /**
     * Acquire a reference to the controller.
     * Establishes connection on first acquire.
     * Returns immediately; check [connected] or use [awaitController] for async access.
     */
    @Synchronized
    fun acquire() {
        val count = refCount.addAndFetch(1)
        logger.debug { "MediaControllerHolder.acquire: refCount=$count" }

        if (count == 1) {
            connect()
        }
    }

    /**
     * Release a reference to the controller.
     * Disconnects when refCount reaches 0.
     */
    @Synchronized
    fun release() {
        val count = refCount.addAndFetch(-1)
        logger.debug { "MediaControllerHolder.release: refCount=$count" }

        if (count <= 0) {
            refCount.store(0) // Prevent negative
            disconnect()
        }
    }

    /**
     * Suspends until a connected [MediaController] is available, bounded by
     * [CONTROLLER_CONNECT_TIMEOUT_MS]. Returns immediately if already connected.
     *
     * If the bound elapses without a connection — e.g. the Media3 service is slow to bind on a
     * cold start, or never binds at all — reports a user-visible failure via [playbackManager]
     * and returns `null`, rather than leaving the caller to silently no-op on a stale snapshot.
     */
    suspend fun awaitController(): MediaController? {
        controller?.let { return it }

        if (!awaitReady(connected, CONTROLLER_CONNECT_TIMEOUT_MS)) {
            logger.error { "MediaControllerHolder.awaitController: timed out waiting for connection" }
            playbackManager.reportError(
                PlaybackError.CouldNotStart(
                    debugInfo = "controller did not connect within $CONTROLLER_CONNECT_TIMEOUT_MS ms",
                ),
            )
            return null
        }
        return controller
    }

    private fun connect() {
        if (connectedController != null || controllerFuture != null) {
            logger.debug { "MediaControllerHolder: already connecting/connected" }
            return
        }

        logger.info { "MediaControllerHolder: establishing connection" }

        val future = connectionFactory(disconnectListener)
        controllerFuture = future
        future.addListener({
            try {
                connectedController = future.get()
                connected.value = true
                connectedController?.addListener(playerListener)
                logger.info { "MediaControllerHolder: connected" }
            } catch (e: Exception) {
                logger.error(e) { "MediaControllerHolder: connection failed" }
                connected.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    /** Tears down the current connection state — shared by [disconnect] and [handleDisconnect]. */
    private fun teardownConnection() {
        connectedController?.removeListener(playerListener)
        connectedController?.release()
        connectedController = null

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        connected.value = false
    }

    private fun disconnect() {
        logger.info { "MediaControllerHolder: disconnecting" }
        teardownConnection()
    }

    /**
     * Called when Media3 reports the connection dropped ([MediaController.Listener.onDisconnected]).
     * A dropped controller must not permanently brick transport for the rest of the process, so
     * this tears down state and — if still acquired — immediately attempts to reconnect. `internal`
     * so tests can trigger it directly without a real Media3 session.
     */
    internal fun handleDisconnect() {
        logger.warn { "MediaControllerHolder: connection dropped" }
        teardownConnection()

        if (refCount.load() > 0) {
            logger.info { "MediaControllerHolder: reconnecting after disconnect" }
            connect()
        }
    }
}
