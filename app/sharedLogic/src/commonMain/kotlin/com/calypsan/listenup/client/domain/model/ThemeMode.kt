package com.calypsan.listenup.client.domain.model

/**
 * User's preferred theme mode for the app.
 *
 * This is a device-local preference that does NOT sync to the server.
 * Each device can have its own theme preference.
 */
enum class ThemeMode {
    /** Follow the system light/dark setting */
    SYSTEM,

    /** Always use light theme */
    LIGHT,

    /** Always use dark theme */
    DARK,

    ;

    companion object {
        /**
         * Parse theme mode from storage string.
         * Returns SYSTEM for any unrecognized value (safe default).
         */
        fun fromString(value: String?): ThemeMode =
            when (value?.lowercase()) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> SYSTEM
            }
    }

    /**
     * Convert to string for storage.
     */
    fun toStorageString(): String = name.lowercase()
}
