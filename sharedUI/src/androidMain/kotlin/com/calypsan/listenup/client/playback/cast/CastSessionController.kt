package com.calypsan.listenup.client.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
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
 * The [castPlayer] (a [SpeedAwareCastPlayer] over Media3's [CastPlayer]) is the
 * player the PlaybackService swaps into its MediaLibrarySession on connect. This
 * class only SIGNALS transitions via [onConnected]/[onDisconnected]; the service
 * performs the swap so the session + local ExoPlayer stay in one place.
 */
@OptIn(UnstableApi::class)
class CastSessionController private constructor(
    private val rawCastPlayer: CastPlayer,
    val castPlayer: Player,
) {
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    init {
        rawCastPlayer.setSessionAvailabilityListener(
            object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    logger.info { "Cast session available" }
                    onConnected?.invoke()
                }

                override fun onCastSessionUnavailable() {
                    logger.info { "Cast session unavailable" }
                    onDisconnected?.invoke()
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
         * button and plays locally.
         */
        fun createOrNull(context: Context): CastSessionController? {
            val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (available != ConnectionResult.SUCCESS) {
                logger.info { "Google Play Services unavailable ($available) — Cast disabled" }
                return null
            }
            return runCatching {
                val castContext = CastContext.getSharedInstance(context)
                val castPlayer = CastPlayer(castContext)
                val rateSetter =
                    CastRateSetter { rate ->
                        castContext.sessionManager.currentCastSession
                            ?.remoteMediaClient
                            ?.setPlaybackRate(rate)
                    }
                CastSessionController(castPlayer, SpeedAwareCastPlayer(castPlayer, rateSetter))
            }.onFailure { logger.warn(it) { "Cast init failed — Cast disabled" } }.getOrNull()
        }
    }
}
