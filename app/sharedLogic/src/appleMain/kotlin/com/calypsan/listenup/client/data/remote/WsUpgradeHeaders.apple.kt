package com.calypsan.listenup.client.data.remote

/** Darwin's `NSURLSessionWebSocketTask` upgrades over a real HTTP request, headers included. */
internal actual val wsUpgradeCarriesHeaders: Boolean = true
