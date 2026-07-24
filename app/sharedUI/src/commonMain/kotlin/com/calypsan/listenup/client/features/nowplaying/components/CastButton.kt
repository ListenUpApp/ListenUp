package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Cast / route-picker button for the Now Playing screen.
 *
 * On Android this embeds the framework `MediaRouteButton`, which handles device
 * discovery, the chooser/controller dialogs, the connecting animation, and
 * auto-hides when no Cast devices are on the network. On platforms without Cast
 * (Desktop) it renders nothing.
 */
@Composable
expect fun CastButton(modifier: Modifier = Modifier)
