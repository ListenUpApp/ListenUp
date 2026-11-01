package com.calypsan.listenup.client.playback

import android.net.Uri
import androidx.media3.common.C

/**
 * The ExoPlayer wake mode a playlist needs.
 *
 * Streaming holds Wi-Fi as well as the CPU: with the screen off, Wi-Fi power-save between buffer refills stalls and
 * reconnects mid-chapter. A playlist of downloaded files needs only the CPU, so it does not pay for a Wi-Fi lock.
 */
internal fun wakeModeFor(uris: List<Uri>): Int =
    if (uris.any { it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true) }) {
        C.WAKE_MODE_NETWORK
    } else {
        C.WAKE_MODE_LOCAL
    }
