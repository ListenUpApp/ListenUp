package com.calypsan.listenup.client.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where audio goes when it does not play on this device: the Cast device's name, or null (spec §4.7). */
interface OutputRoute {
    val deviceName: StateFlow<String?>
}

/** For platforms with no remote output the app controls (desktop, web). Internal, like [NoPlaybackStartGate]. */
internal object NoOutputRoute : OutputRoute {
    override val deviceName: StateFlow<String?> = MutableStateFlow(null)
}
