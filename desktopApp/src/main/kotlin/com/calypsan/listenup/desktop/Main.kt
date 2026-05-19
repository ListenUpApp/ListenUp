package com.calypsan.listenup.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.calypsan.listenup.client.di.platformModule
import com.calypsan.listenup.client.di.playbackPresentationModule
import com.calypsan.listenup.client.di.sharedModules
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.desktop.di.desktopAppModule
import com.calypsan.listenup.desktop.media.GlobalMediaKeyManager
import com.calypsan.listenup.desktop.window.ListenUpWindow
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting ListenUp Desktop..." }

    // Initialize Koin DI
    startKoin {
        modules(
            sharedModules + // From :shared module
                platformModule + // From :composeApp desktopMain
                playbackPresentationModule + // Shared playback VM bindings
                desktopAppModule, // Desktop app specific
        )
    }

    // Start global media key listener (non-critical, may fail on some systems)
    val mediaKeyManager =
        try {
            getKoin().get<GlobalMediaKeyManager>().also { it.start() }
        } catch (e: Exception) {
            logger.warn(e) { "Global media keys unavailable" }
            null
        }

    logger.info { "Koin initialized, launching application..." }

    application {
        val windowState =
            rememberWindowState(
                size = DpSize(1280.dp, 800.dp),
            )

        ListenUpWindow(
            state = windowState,
            onCloseRequest = {
                mediaKeyManager?.stop()
                getKoin().get<AudioPlayer>().release()
                exitApplication()
            },
        )
    }
}
