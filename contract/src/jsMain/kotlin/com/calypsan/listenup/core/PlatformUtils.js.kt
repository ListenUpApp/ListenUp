package com.calypsan.listenup.core

import kotlinx.browser.window

/**
 * Browser implementation of PlatformUtils.
 *
 * Follows the `linuxMain` precedent of answering plainly rather than faking a device:
 * a browser is not an emulator, and its "model" is the user agent string.
 *
 * Note: no `@HiddenFromObjC` here. That annotation works on the `expect` because it is an
 * `@OptionalExpectation` annotation, but `kotlin.native.HiddenFromObjC` has no js actual and
 * referencing it from `jsMain` will not resolve.
 */
actual object PlatformUtils {
    actual fun isEmulator(): Boolean = false

    actual fun getDeviceModel(): String = window.navigator.userAgent

    actual fun getPlatformName(): String = "Web"

    actual fun getPlatformVersion(): String = window.navigator.appVersion
}
