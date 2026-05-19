package com.calypsan.listenup.client.playback

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger = KotlinLogging.logger {}

/**
 * Singleton holder for the MediaController connection.
 *
 * NowPlayingViewModel consumes this controller via the PlaybackController seam
 * (PlayerViewModel was deleted in W7 Phase E2.2.3). The holder also owns the
 * Player.Listener and position polling that drive PlaybackStateWriter on Android.
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
    private val scope: CoroutineScope,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var _controller: MediaController? = null

    val isConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val refCount = AtomicInt(0)

    /**
     * Get the current MediaController, or null if not connected.
     */
    val controller: MediaController?
        get() = _controller

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
                val isNetworkError =
                    error.errorCode in
                        listOf(
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        )
                val message =
                    if (isNetworkError) {
                        "Couldn't connect to server. Download this book for offline listening."
                    } else {
                        "Playback error: ${error.localizedMessage ?: "Unknown error"}"
                    }
                playbackManager.reportError(message = message, isRecoverable = isNetworkError)
                playbackManager.setPlaying(false)
                logger.error { "ExoPlayer error: ${error.errorCodeName} - ${error.message}" }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                playbackManager.updateSpeed(playbackParameters.speed)
            }
        }

    private fun toCommonPlaybackState(media3State: Int): PlaybackState =
        when (media3State) {
            Player.STATE_IDLE -> PlaybackState.Idle
            Player.STATE_BUFFERING -> PlaybackState.Buffering
            Player.STATE_READY -> if (_controller?.isPlaying == true) PlaybackState.Playing else PlaybackState.Paused
            Player.STATE_ENDED -> PlaybackState.Ended
            else -> PlaybackState.Idle
        }

    private var positionPollJob: Job? = null

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 250L
    }

    internal fun startPositionPolling(controller: MediaController) {
        positionPollJob?.cancel()
        positionPollJob =
            scope.launch {
                while (isActive) {
                    if (controller.isPlaying) {
                        playbackManager.updatePosition(controller.currentPosition)
                    }
                    delay(POSITION_POLL_INTERVAL_MS)
                }
            }
    }

    /**
     * Acquire a reference to the controller.
     * Establishes connection on first acquire.
     * Returns immediately; check [isConnected] or use [awaitController] for async access.
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
     * Execute an action with the controller when it becomes available.
     * If already connected, executes immediately on the current thread.
     * If not connected, queues the action for when connection completes.
     */
    fun withController(action: (MediaController) -> Unit) {
        val current = _controller
        if (current != null) {
            action(current)
            return
        }

        // Queue for when connected
        controllerFuture?.addListener({
            _controller?.let(action)
        }, MoreExecutors.directExecutor())
    }

    private fun connect() {
        if (_controller != null || controllerFuture != null) {
            logger.debug { "MediaControllerHolder: already connecting/connected" }
            return
        }

        logger.info { "MediaControllerHolder: establishing connection" }

        val sessionToken =
            SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                _controller = controllerFuture?.get()
                isConnected.value = true
                _controller?.let { newController ->
                    newController.addListener(playerListener)
                    startPositionPolling(newController)
                }
                logger.info { "MediaControllerHolder: connected" }
            } catch (e: Exception) {
                logger.error(e) { "MediaControllerHolder: connection failed" }
                isConnected.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    private fun disconnect() {
        logger.info { "MediaControllerHolder: disconnecting" }

        positionPollJob?.cancel()
        positionPollJob = null
        _controller?.removeListener(playerListener)

        _controller?.release()
        _controller = null

        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        isConnected.value = false
    }
}
