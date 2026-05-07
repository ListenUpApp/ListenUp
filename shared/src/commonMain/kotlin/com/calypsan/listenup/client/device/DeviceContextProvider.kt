package com.calypsan.listenup.client.device

/**
 * Platform-specific factory that classifies the running device into a [DeviceContext].
 * Each platform's `actual` consults OS-level signals (UI mode, screen metrics, package
 * features) to pick the right [DeviceType].
 */
expect class DeviceContextProvider {
    fun detect(): DeviceContext
}
