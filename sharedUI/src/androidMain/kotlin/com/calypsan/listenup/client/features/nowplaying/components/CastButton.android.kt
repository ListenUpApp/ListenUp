package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
actual fun CastButton(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).also { button ->
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, button)
            }
        },
    )
}
