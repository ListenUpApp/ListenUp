package com.calypsan.listenup.desktop.media

import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.playback.NowPlayingState
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.logging.Level
import java.util.logging.Logger

private val logger = KotlinLogging.logger {}

/**
 * Manages global media key detection via JNativeHook.
 *
 * Listens for system-wide media key events (Play/Pause, Next, Previous)
 * and forwards them to the player, even when the window is not focused.
 *
 * Call [start] to begin listening and [stop] to clean up.
 */
class GlobalMediaKeyManager(
    private val nowPlayingViewModel: NowPlayingViewModel,
) {
    private var isRegistered = false

    private val keyListener =
        object : NativeKeyListener {
            override fun nativeKeyPressed(event: NativeKeyEvent) {
                // Only intercept global media keys when there's a book loaded.
                if (nowPlayingViewModel.screenState.value.state is NowPlayingState.Idle) return

                when (event.keyCode) {
                    NativeKeyEvent.VC_MEDIA_PLAY -> {
                        logger.debug { "Global media key: Play/Pause" }
                        nowPlayingViewModel.playPause()
                    }

                    NativeKeyEvent.VC_MEDIA_NEXT -> {
                        logger.debug { "Global media key: Next" }
                        nowPlayingViewModel.skipForward()
                    }

                    NativeKeyEvent.VC_MEDIA_PREVIOUS -> {
                        logger.debug { "Global media key: Previous" }
                        nowPlayingViewModel.skipBack()
                    }

                    NativeKeyEvent.VC_MEDIA_STOP -> {
                        logger.debug { "Global media key: Stop" }
                        nowPlayingViewModel.closeBook()
                    }
                }
            }
        }

    /**
     * Register the global native hook and start listening for media keys.
     * Fails gracefully if native hook registration is not possible.
     */
    fun start() {
        if (isRegistered) return

        // Suppress JNativeHook's internal logging (very verbose)
        Logger.getLogger(GlobalScreen::class.java.`package`.name).level = Level.OFF

        try {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(keyListener)
            isRegistered = true
            logger.info { "Global media key listener registered" }
        } catch (e: NativeHookException) {
            logger.warn(e) { "Failed to register global media keys (not critical)" }
        }
    }

    /**
     * Unregister the global hook and stop listening.
     */
    fun stop() {
        if (!isRegistered) return

        try {
            GlobalScreen.removeNativeKeyListener(keyListener)
            GlobalScreen.unregisterNativeHook()
            isRegistered = false
            logger.info { "Global media key listener unregistered" }
        } catch (e: NativeHookException) {
            logger.warn(e) { "Failed to unregister global media keys" }
        }
    }
}
