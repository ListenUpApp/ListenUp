package com.calypsan.listenup.client.data.remote

/**
 * The browser's `WebSocket` constructor is `(url, protocols)` — there is no header parameter, and
 * `ktor-client-js` reflects that by discarding the headers it was handed. The credential has to
 * ride the URL instead; see [rpcMountUrl].
 */
internal actual val wsUpgradeCarriesHeaders: Boolean = false
