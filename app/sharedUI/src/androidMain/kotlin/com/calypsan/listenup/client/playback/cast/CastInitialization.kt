package com.calypsan.listenup.client.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Cast configuration, stated explicitly.
 *
 * Every field [CastParams] leaves unset inherits a Media3 default, and two of those defaults
 * would silently change what the app does — see `CastParamsPolicyTest`, which pins both.
 *
 * We cast to the plain Default Media Receiver: no Cast Developer Console registration, no app id
 * to manage, and no DRM machinery we have no use for.
 */
@OptIn(UnstableApi::class)
internal fun listenUpCastParams(): CastParams =
    CastParams
        .Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // Keep the cast button opening the Cast device dialog. Targeting SDK 37 would otherwise
        // opt us into the system output switcher on API 37+ devices — plausibly the better UX, but
        // a product decision that deserves a device in hand, not a dependency bump.
        .setShowSystemOutputSwitcherOnCastButtonClick(false)
        .build()

/**
 * Initializes Cast for the process. Call once, from `Application.onCreate` on the main thread.
 *
 * Media3 1.11 made [Cast] the entry point, and `RemoteCastPlayer` now calls
 * `Cast.ensureInitialized` itself. Without this call that fallback configures Cast from the
 * manifest `OPTIONS_PROVIDER_CLASS_NAME` — which Media3 documents as unsupported, because the
 * manifest path brings the Cast SDK's own automatic media session management along with it, and
 * this app already has exactly one session in `PlaybackService`.
 *
 * Loading is asynchronous inside Media3, so this does not block startup, and a
 * `RemoteCastPlayer` built later simply finds initialization already under way.
 *
 * No Google Play Services means no Cast: we skip initialization, [CastSessionController.createOrNull]
 * returns null, no cast button is shown, and local playback is untouched. Never stranded.
 */
@OptIn(UnstableApi::class)
fun initializeCast(context: Context) {
    if (!isCastSupported(context)) {
        logger.info {
            "Google Play Services missing or older than $MIN_CAST_PLAY_SERVICES_VERSION — Cast not initialized"
        }
        return
    }
    runCatching {
        Cast.getSingletonInstance(context).initialize(listenUpCastParams())
    }.onFailure { logger.warn(it) { "Cast initialization failed — Cast disabled" } }
}

/**
 * The oldest Google Play services (its APK version code, 23.0.0) that Cast may load against.
 *
 * Cast loads its context on a background thread, and Play services as old as 20.18 (still shipped,
 * never updated, on some Android 11 devices without the Play Store) insists that thread be the main
 * one. It throws there, outside any code of ours, and takes the whole process down at launch. Any
 * device that updates Play services is far past this floor.
 */
private const val MIN_CAST_PLAY_SERVICES_VERSION = 230_000_000

/**
 * Whether Cast can be used here: Google Play services is present and new enough to load it. Every
 * Cast entry point checks this first; when it is false there is no cast button, and local playback
 * is untouched. Never stranded.
 */
internal fun isCastSupported(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context, MIN_CAST_PLAY_SERVICES_VERSION) ==
        ConnectionResult.SUCCESS
