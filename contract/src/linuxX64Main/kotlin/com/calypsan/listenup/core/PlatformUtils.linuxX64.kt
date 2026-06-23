@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.core

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * linuxX64 (native server) implementation of PlatformUtils.
 *
 * The server is not a client device — emulator detection is meaningless,
 * and the other fields carry server-context values instead.
 */
@HiddenFromObjC
actual object PlatformUtils {
    actual fun isEmulator(): Boolean = false

    actual fun getDeviceModel(): String = "server"

    actual fun getPlatformName(): String = "Linux"

    actual fun getPlatformVersion(): String = ""
}
