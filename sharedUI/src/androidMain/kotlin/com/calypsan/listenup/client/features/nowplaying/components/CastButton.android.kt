package com.calypsan.listenup.client.features.nowplaying.components

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

@Composable
actual fun CastButton(modifier: Modifier) {
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
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // MediaRouteButton's constructor computes controller contrast against the theme's
            // android:colorBackground, which the edge-to-edge Compose window leaves translucent
            // (#0) — crashing the button. Build it in an opaque AppCompat theme instead.
            val themed = ContextThemeWrapper(ctx, R.style.Theme_ListenUp_CastButton)
            MediaRouteButton(themed).also { button ->
                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, button)
            }
        },
    )
}
