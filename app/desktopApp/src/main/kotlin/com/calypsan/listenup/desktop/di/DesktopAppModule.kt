package com.calypsan.listenup.desktop.di

import com.calypsan.listenup.desktop.media.GlobalMediaKeyManager
import org.koin.dsl.module

/**
 * Desktop application-specific Koin module.
 *
 * Provides desktop-only dependencies that don't belong in :composeApp's platformModule.
 * Examples:
 * - Global media key handling
 * - Window state management
 * - System tray controller
 * - Desktop preferences (window size/position)
 *
 * Most platform dependencies are provided by:
 * - :shared module (repositories, use cases, data layer)
 * - :composeApp platformModule (playback, downloads, background sync)
 */
val desktopAppModule =
    module {
        // Global media key listener (play/pause/next/prev when app is in background)
        single { GlobalMediaKeyManager(nowPlayingViewModel = get()) }
    }
