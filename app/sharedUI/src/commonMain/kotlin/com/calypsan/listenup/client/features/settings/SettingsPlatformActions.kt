package com.calypsan.listenup.client.features.settings

/**
 * Platform-specific side-effect actions for the Settings screen.
 *
 * Android: shares the on-device log files through the system share sheet (FileProvider).
 * Desktop: reveals the log directory in the file manager (clipboard-copies the path as
 * a fallback). iOS renders its own native settings in SwiftUI, so it does not consume
 * this interface — its share affordance plugs into the same log files once an iOS log
 * tap exists (see [com.calypsan.listenup.client.core.logging.LogSinkRegistry]).
 */
interface SettingsPlatformActions {
    /** Export the persistent app log files for troubleshooting. */
    fun shareLogs()
}
