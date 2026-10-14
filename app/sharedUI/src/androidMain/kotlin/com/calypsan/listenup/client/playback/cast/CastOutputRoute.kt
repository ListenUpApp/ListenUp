package com.calypsan.listenup.client.playback.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.client.playback.OutputRoute
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val logger = KotlinLogging.logger {}

/**
 * The Cast device's friendly name while a Cast session is connected (spec §4.7, "Playing on Living Room").
 *
 * It listens through Media3's [Cast] singleton, the same entry point [initializeCast] configures, so it shares
 * that CastContext rather than loading a second one; a listener added before the context finishes loading is
 * held by [Cast] and attached when it does. No Google Play Services, or a context that fails to load: there is
 * never a route, and playback is untouched.
 */
@OptIn(UnstableApi::class)
internal class CastOutputRoute(
    private val context: Context,
) : OutputRoute {
    override val deviceName: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    private val listener =
        object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(
                session: CastSession,
                sessionId: String,
            ) {
                deviceName.value = session.castDevice?.friendlyName
            }

            override fun onSessionResumed(
                session: CastSession,
                wasSuspended: Boolean,
            ) {
                deviceName.value = session.castDevice?.friendlyName
            }

            override fun onSessionEnded(
                session: CastSession,
                error: Int,
            ) {
                deviceName.value = null
            }

            override fun onSessionSuspended(
                session: CastSession,
                reason: Int,
            ) {
                deviceName.value = null
            }

            override fun onSessionStarting(session: CastSession) = Unit

            override fun onSessionStartFailed(
                session: CastSession,
                error: Int,
            ) = Unit

            override fun onSessionEnding(session: CastSession) = Unit

            override fun onSessionResuming(
                session: CastSession,
                sessionId: String,
            ) = Unit

            override fun onSessionResumeFailed(
                session: CastSession,
                error: Int,
            ) = Unit
        }

    init {
        // Cast's session listeners are main-thread only. Koin builds this inside startKoin in
        // Application.onCreate (the session that needs it is created at start), which is the main thread, as
        // initializeCast is; anything resolving it elsewhere hands the attach to the main looper.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach()
        } else {
            Handler(Looper.getMainLooper()).post(::attach)
        }
    }

    private fun attach() {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) return
        runCatching {
            val cast = Cast.getSingletonInstance(context)
            deviceName.value = cast.currentCastSession?.castDevice?.friendlyName
            cast.addSessionManagerListener(listener)
        }.onFailure { logger.warn(it) { "Cast route unavailable; the player will not name a Cast device" } }
    }
}
