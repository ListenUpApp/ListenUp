@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.core

import android.os.Build

/**
 * Android implementation of PlatformUtils.
 *
 * Emulator detection uses multiple heuristics to catch all Android emulator types:
 * - Official Android Emulator (FINGERPRINT contains "generic")
 * - Genymotion (MANUFACTURER contains "Genymotion")
 * - Hardware indicators (goldfish, ranchu)
 * - Product name patterns
 *
 * This approach is more reliable than checking a single property.
 */
actual object PlatformUtils {
    actual fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.PRODUCT == "google_sdk" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    /**
     * Returns the Android device model name.
     *
     * Uses Build.MODEL which returns user-visible device names like:
     * - "Pixel 7 Pro"
     * - "SM-G998B" (Samsung Galaxy S21 Ultra)
     * - "sdk_gphone64_arm64" (emulator)
     */
    actual fun getDeviceModel(): String = Build.MODEL

    /**
     * Returns the platform name for Android.
     */
    actual fun getPlatformName(): String = "Android"

    /**
     * Returns the Android SDK version as a string.
     *
     * Uses Build.VERSION.SDK_INT which returns the API level (e.g., 33, 34).
     */
    actual fun getPlatformVersion(): String = Build.VERSION.SDK_INT.toString()
}
