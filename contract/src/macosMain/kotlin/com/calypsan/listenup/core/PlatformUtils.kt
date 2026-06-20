@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.core

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import platform.Foundation.NSProcessInfo

/**
 * macOS implementation of PlatformUtils.
 *
 * Uses NSProcessInfo for system information.
 * Desktop is never an emulator.
 */
@HiddenFromObjC
actual object PlatformUtils {
    actual fun isEmulator(): Boolean = false

    /**
     * Returns the macOS host name as device model identifier.
     */
    actual fun getDeviceModel(): String {
        val info = NSProcessInfo.processInfo
        return "macOS (${info.hostName})"
    }

    /**
     * Returns the platform name for macOS.
     */
    actual fun getPlatformName(): String = "macOS"

    /**
     * Returns the macOS version string.
     * Example: "14.2.0"
     */
    actual fun getPlatformVersion(): String {
        val version = NSProcessInfo.processInfo.operatingSystemVersionString
        return version
    }
}
