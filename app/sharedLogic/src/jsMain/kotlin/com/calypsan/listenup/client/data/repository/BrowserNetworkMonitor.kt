package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browser connectivity over `navigator.onLine` and the `online`/`offline` window events.
 *
 * `navigator.onLine` is a lower bound on truth — `true` means "some network interface exists",
 * not "the server is reachable" — but that is the same contract the native monitors offer:
 * reachability of the actual server is the RPC channel's business, not this seam's.
 *
 * Metering is deliberately treated as unmetered-when-online: the Network Information API is
 * Chromium-only, and the only consumer of the unmetered signal is the download queue, which
 * does not exist on web (browser downloads are undesigned — see `DownloadFileManager.js`).
 */
class BrowserNetworkMonitor : NetworkMonitor {
    private val online = MutableStateFlow(window.navigator.onLine)

    init {
        window.addEventListener("online", { online.value = true })
        window.addEventListener("offline", { online.value = false })
    }

    override fun isOnline(): Boolean = window.navigator.onLine

    override val isOnlineFlow: StateFlow<Boolean> = online.asStateFlow()

    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = online.asStateFlow()
}
