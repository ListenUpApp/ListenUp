package com.calypsan.listenup.core

import platform.UIKit.UIDevice

/**
 * iOS implementation of PlatformUtils.
 *
 * Simulator detection checks if device name contains "Simulator".
 * This is the standard approach for iOS and works reliably across
 * all simulator types (iPhone, iPad, etc.).
 */
actual object PlatformUtils {
    actual fun isEmulator(): Boolean = UIDevice.currentDevice.name.contains("Simulator", ignoreCase = true)

    /**
     * Returns the iOS device model name.
     *
     * Uses UIDevice.currentDevice.model which returns device type names like:
     * - "iPhone"
     * - "iPad"
     * - "iPod touch"
     *
     * Note: For more specific model info (e.g., "iPhone 15 Pro"),
     * we would need to use sysctlbyname("hw.machine") and map identifiers.
     * For session tracking purposes, the general device type is sufficient.
     */
    actual fun getDeviceModel(): String = UIDevice.currentDevice.model

    /**
     * Returns the platform name for iOS.
     */
    actual fun getPlatformName(): String = "iOS"

    /**
     * Returns the iOS version string.
     *
     * Uses UIDevice.currentDevice.systemVersion which returns version strings like:
     * - "17.2"
     * - "16.4.1"
     */
    actual fun getPlatformVersion(): String = UIDevice.currentDevice.systemVersion
}
