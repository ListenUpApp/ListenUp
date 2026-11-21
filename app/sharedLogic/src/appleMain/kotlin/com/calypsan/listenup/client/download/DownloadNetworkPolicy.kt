package com.calypsan.listenup.client.download

import platform.Foundation.NSMutableURLRequest

/**
 * Apply the user's "Download on Wi-Fi Only" preference to a single download request.
 *
 * **Expensive, not cellular.** Android gates downloads on *metering* (`NetworkType.UNMETERED`) and
 * `AppleNetworkMonitor` already derives "unmetered" from `nw_path_is_expensive`, so
 * `allowsExpensiveNetworkAccess` is the knob that matches both. `allowsCellularAccess` would permit
 * a metered personal hotspot that every other part of this app already treats as expensive.
 *
 * The session's configuration carries the same policy and is the authority
 * ([backgroundDownloadConfiguration]): a task resumed from resume data replays the request it started
 * with, so only the session can still refuse a metered network for it. The request carries it too,
 * so a new task never rests on the undocumented interplay of the two alone.
 */
internal fun NSMutableURLRequest.applyDownloadNetworkPolicy(wifiOnlyDownloads: Boolean) {
    // Kotlin/Native binds NSURLRequest's readonly property as a `val` and NSMutableURLRequest's
    // setter as a function, so this cannot be an assignment even though ObjC declares a readwrite
    // property. The getter the tests read is the `val` half of that same pair.
    setAllowsExpensiveNetworkAccess(!wifiOnlyDownloads)
}
