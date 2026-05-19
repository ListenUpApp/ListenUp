package com.calypsan.listenup.core

/**
 * Platform-specific utilities for cross-platform code.
 *
 * Uses expect/actual pattern to provide platform-specific implementations
 * while maintaining a clean API for common code.
 *
 * Currently provides:
 * - isEmulator(): Detects if app is running on emulator/simulator
 * - getDeviceModel(): Gets the device model name for identification
 * - getPlatformName(): Gets the platform name ("Android" or "iOS")
 * - getPlatformVersion(): Gets the OS version string
 */
expect object PlatformUtils {
    /**
     * Checks if the app is running on an emulator/simulator.
     *
     * Used for validation logic that differs between physical devices
     * and emulators (e.g., localhost URLs only work on emulators).
     *
     * @return true if running on emulator/simulator, false otherwise
     */
    fun isEmulator(): Boolean

    /**
     * Gets the device model name.
     *
     * Used for device identification in authentication and session tracking.
     * Examples: "Pixel 7 Pro", "iPhone 15 Pro", "SM-G998B"
     *
     * @return Device model name string
     */
    fun getDeviceModel(): String

    /**
     * Gets the platform name.
     *
     * Used for device identification in authentication requests.
     *
     * @return "Android" on Android devices, "iOS" on iOS devices
     */
    fun getPlatformName(): String

    /**
     * Gets the platform OS version.
     *
     * Used for device identification in authentication requests.
     * Examples: "14" (Android API), "17.2" (iOS version)
     *
     * @return Platform-specific version string
     */
    fun getPlatformVersion(): String
}
