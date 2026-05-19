package com.calypsan.listenup.core

/**
 * JVM desktop implementation of PlatformUtils.
 *
 * Uses System properties to detect platform information.
 * Desktop is never an emulator.
 */
actual object PlatformUtils {
    actual fun isEmulator(): Boolean = false

    /**
     * Returns the OS name and architecture.
     * Examples: "Windows 11 (amd64)", "Linux (amd64)"
     */
    actual fun getDeviceModel(): String {
        val os = System.getProperty("os.name", "Unknown")
        val arch = System.getProperty("os.arch", "Unknown")
        return "$os ($arch)"
    }

    /**
     * Returns the platform name for device identification.
     * Examples: "Windows", "Linux"
     */
    actual fun getPlatformName(): String {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            "windows" in osName -> "Windows"
            "linux" in osName -> "Linux"
            "mac" in osName -> "macOS"
            else -> "Desktop"
        }
    }

    /**
     * Returns the OS version string.
     * Examples: "10.0", "6.5.0-arch1-1"
     */
    actual fun getPlatformVersion(): String = System.getProperty("os.version", "Unknown")
}
