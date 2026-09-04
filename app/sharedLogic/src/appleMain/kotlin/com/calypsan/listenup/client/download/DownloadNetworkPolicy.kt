package com.calypsan.listenup.client.download

import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLSessionConfiguration

/** Per-request timeout for download tasks, in seconds. */
private const val REQUEST_TIMEOUT_SECONDS = 60.0

/** Whole-resource timeout for a single download, in seconds (1 hour for large files). */
private const val RESOURCE_TIMEOUT_SECONDS = 3600.0

/**
 * The configuration behind the download session.
 *
 * [NSURLSessionConfiguration.waitsForConnectivity] is the half that makes "Download on Wi-Fi Only"
 * a *pause* rather than a failure. A request that is denied an expensive network waits for a
 * satisfactory one instead of erroring out — which is what the setting's own subtitle promises
 * ("Pause downloads on cellular networks") and what Android's `NetworkType.UNMETERED` constraint
 * already does by holding the work.
 *
 * The wifi-only preference itself is deliberately NOT set here. A configuration is copied when its
 * session is created, and that session is built once, so a flag set here would freeze the
 * preference at construction and never see the user toggle it. It goes on each request instead —
 * see [applyDownloadNetworkPolicy].
 */
internal fun downloadSessionConfiguration(): NSURLSessionConfiguration =
    NSURLSessionConfiguration.defaultSessionConfiguration.apply {
        timeoutIntervalForRequest = REQUEST_TIMEOUT_SECONDS
        timeoutIntervalForResource = RESOURCE_TIMEOUT_SECONDS
        waitsForConnectivity = true
    }

/**
 * Apply the user's "Download on Wi-Fi Only" preference to a single download request.
 *
 * **Expensive, not cellular.** Android gates downloads on *metering* (`NetworkType.UNMETERED`) and
 * `AppleNetworkMonitor` already derives "unmetered" from `nw_path_is_expensive`, so
 * `allowsExpensiveNetworkAccess` is the knob that matches both. `allowsCellularAccess` would permit
 * a metered personal hotspot that every other part of this app already treats as expensive.
 *
 * Applied per request rather than on the session configuration so the preference is read live: the
 * session outlives any number of trips through Settings.
 */
internal fun NSMutableURLRequest.applyDownloadNetworkPolicy(wifiOnlyDownloads: Boolean) {
    // Kotlin/Native binds NSURLRequest's readonly property as a `val` and NSMutableURLRequest's
    // setter as a function, so this cannot be an assignment even though ObjC declares a readwrite
    // property. The getter the tests read is the `val` half of that same pair.
    setAllowsExpensiveNetworkAccess(!wifiOnlyDownloads)
}
