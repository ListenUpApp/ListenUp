package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/*
 * The bounds that keep a stalled stream from stranding a listener mid-book.
 *
 * A stream can stop delivering bytes without ever failing — a Wi-Fi→cellular handover leaves a
 * half-open socket that accepts a read and never answers it. Nothing in that story throws. Unless
 * something imposes a deadline, OkHttp blocks forever, ExoPlayer stays in STATE_BUFFERING, no
 * PlaybackException is raised, and PlaybackErrorHandler's network-retry path — correct, tested,
 * and entirely unreachable — never runs. The listener sees a spinner until they force-quit.
 *
 * So there are deadlines at three layers, each catching what the one before it cannot:
 *
 * 1. STREAM_READ_TIMEOUT_MS bounds a single socket read. This is the one that catches the case
 *    above, and it is where recovery is cheapest: OkHttp raises an IOException, ExoPlayer maps it
 *    to ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, and the retry path takes over — usually before
 *    the buffer drains, so the listener hears nothing at all.
 * 2. STUCK_BUFFERING_TIMEOUT_MS is the backstop for a stall that isn't a socket read.
 * 3. needsPrepareBeforePlay keeps the manual fallback working once automatic recovery gives up.
 */

/** Bounds establishing a connection to the audio source. */
internal const val STREAM_CONNECT_TIMEOUT_MS = 30_000

/**
 * Bounds a single socket read, so a half-open connection surfaces as a retryable error.
 *
 * This does not fight ExoPlayer's backpressure. Once the buffer is full ExoPlayer stops calling
 * `read()` altogether and the socket sits idle without any read in flight; the timeout only bounds
 * a read already underway, and each new read starts a fresh window.
 */
internal const val STREAM_READ_TIMEOUT_MS = 30_000

/** Bounds writing a request. Audio streaming requests are small, so this is a formality. */
internal const val STREAM_WRITE_TIMEOUT_MS = 30_000

/**
 * How long the player may sit buffering before Media3 declares it stuck.
 *
 * Media3's own default (`ExoPlayer.Builder.DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS`) is ten
 * minutes, which is abandonment rather than a backstop. Two minutes is the floor set by layer 1:
 * this must clear one full [STREAM_READ_TIMEOUT_MS] *plus* the retry-and-backoff window that
 * follows it, or the backstop would fire while a legitimate retry is still in flight and convert a
 * recoverable blip into a user-visible error.
 */
internal const val STUCK_BUFFERING_TIMEOUT_MS = 120_000

/**
 * Whether a play command must re-prepare the player before it will produce audio.
 *
 * `play()` on an idle player sets `playWhenReady` and returns — the player stays idle and nothing
 * is heard. A player is left idle by exactly the situations this file exists for: a terminal
 * playback error, or the stop/prepare cycle [PlaybackErrorHandler] runs when recovering. So the
 * manual "tap play to try again" that the listener is offered when automatic recovery gives up
 * would itself do nothing without this check — the fallback behind the fallback.
 */
internal fun needsPrepareBeforePlay(playbackState: Int): Boolean = playbackState == Player.STATE_IDLE

/**
 * Builds the OkHttp client Media3 streams audio through, with every socket timeout bounded.
 *
 * [authInterceptor] stamps the bearer token onto each request; [tokenAuthenticator] refreshes it
 * and re-issues on 401. Both come from [AndroidAudioTokenProvider].
 */
internal fun buildStreamingHttpClient(
    authInterceptor: Interceptor,
    tokenAuthenticator: Authenticator,
): OkHttpClient =
    OkHttpClient
        .Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .connectTimeout(STREAM_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(STREAM_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(STREAM_WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
