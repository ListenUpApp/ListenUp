package com.calypsan.listenup.client.features.nowplaying.components

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.calypsan.listenup.client.composeapp.R
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@Composable
actual fun CastButton(
    connectedDeviceName: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // Mirror CastSessionController.createOrNull's guard: on a de-Googled device (no Play Services)
    // CastButtonFactory can reach into CastContext and throw. No Play Services → no cast button,
    // local playback unaffected (never stranded).
    val available =
        remember(context) {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
        }
    if (!available) return
    // One route button for both looks: drawn as itself at rest, and the dialog opener behind the connected chip.
    val routeButton = remember(context) { castRouteButton(context) }
    if (connectedDeviceName != null) {
        // showDialog opens the controller dialog for the connected route (androidx.mediarouter's MediaRouteButton).
        CastConnectedButton(
            deviceName = connectedDeviceName,
            onOpenRoutes = { routeButton.showDialog() },
            modifier = modifier,
        )
    } else {
        AndroidView(modifier = modifier, factory = { routeButton })
    }
}

/**
 * The framework route button. MediaRouteButton's constructor computes controller contrast against the theme's
 * android:colorBackground, which the edge-to-edge Compose window leaves translucent (#0) — crashing the button — so it
 * is built in an opaque AppCompat theme instead.
 */
private fun castRouteButton(context: Context): MediaRouteButton {
    val themed = ContextThemeWrapper(context, R.style.Theme_ListenUp_CastButton)
    return MediaRouteButton(themed).also { button ->
        // The Executor overload, not the plain one: the plain one resolves the CastContext
        // synchronously and throws IllegalStateException unless Cast was configured from the
        // manifest. Cast is now initialized in ListenUp.onCreate (see initializeCast), which
        // Media3 loads asynchronously — so this awaits that load instead of racing it.
        CastButtonFactory
            .setUpMediaRouteButton(context.applicationContext, context.mainExecutor, button)
            .addOnFailureListener { failure ->
                // Left unwired rather than crashing. MediaRouteButton shows nothing until it
                // has a route selector, so the user sees no cast affordance and plays
                // locally — never stranded.
                logger.warn(failure) { "Cast button setup failed — cast unavailable" }
            }
    }
}
