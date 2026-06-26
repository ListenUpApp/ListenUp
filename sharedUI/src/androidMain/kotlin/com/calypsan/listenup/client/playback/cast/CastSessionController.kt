package com.calypsan.listenup.client.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Owns the Cast session lifecycle. Built lazily and only when Google Play
 * Services is available — on a de-Googled device this is a no-op and the app
 * plays locally, untouched (never stranded).
 *
 * The [castPlayer] (a [SpeedAwareCastPlayer] over Media3's [RemoteCastPlayer]) is
 * the player the PlaybackService swaps into its MediaLibrarySession on connect.
 * This class only SIGNALS transitions via the [onConnected]/[onDisconnected]
 * callbacks supplied to [createOrNull]; the service performs the swap so the
 * session + local ExoPlayer stay in one place. The callbacks are registered into
 * the session-availability listener in the constructor, so no transition can fire
 * before they're wired.
 */
@OptIn(UnstableApi::class)
class CastSessionController private constructor(
    private val rawCastPlayer: RemoteCastPlayer,
    val castPlayer: Player,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    /**
     * Whether a Cast session is live right now. Read after a suspension (e.g. the network
     * re-prepare) to confirm the session didn't drop in the gap before swapping onto it.
     */
    val isSessionAvailable: Boolean
        get() = rawCastPlayer.isCastSessionAvailable

    init {
        rawCastPlayer.setSessionAvailabilityListener(
            object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    logger.info { "Cast session available" }
                    onConnected()
                }

                override fun onCastSessionUnavailable() {
                    logger.info { "Cast session unavailable" }
                    onDisconnected()
                }
            },
        )
    }

    fun release() {
        rawCastPlayer.setSessionAvailabilityListener(null)
        rawCastPlayer.release()
    }

    companion object {
        /**
         * Returns a controller, or null when Google Play Services / Cast is
         * unavailable (de-Googled device) — caller then never shows a cast
         * button and plays locally. [onConnected]/[onDisconnected] fire on Cast
         * session transitions; they're registered in the constructor so no
         * transition can be missed.
         */
        fun createOrNull(
            context: Context,
            onConnected: () -> Unit,
            onDisconnected: () -> Unit,
        ): CastSessionController? {
            val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (available != ConnectionResult.SUCCESS) {
                logger.info { "Google Play Services unavailable ($available) — Cast disabled" }
                return null
            }
            return runCatching {
                val castContext = CastContext.getSharedInstance(context)
                val remotePlayer = RemoteCastPlayer.Builder(context).build()
                val rateSetter =
                    CastRateSetter { rate ->
                        castContext.sessionManager.currentCastSession
                            ?.remoteMediaClient
                            ?.setPlaybackRate(rate)
                    }
                CastSessionController(
                    rawCastPlayer = remotePlayer,
                    castPlayer = SpeedAwareCastPlayer(remotePlayer, rateSetter),
                    onConnected = onConnected,
                    onDisconnected = onDisconnected,
                )
            }.onFailure { logger.warn(it) { "Cast init failed — Cast disabled" } }.getOrNull()
        }
    }
}
