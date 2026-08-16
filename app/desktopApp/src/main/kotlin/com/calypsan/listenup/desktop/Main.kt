package com.calypsan.listenup.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.calypsan.listenup.client.core.logging.FileLogSink
import com.calypsan.listenup.client.core.logging.LogSinkRegistry
import com.calypsan.listenup.client.di.desktopDownloadModule
import com.calypsan.listenup.client.di.desktopPlaybackModule
import com.calypsan.listenup.client.di.jvmPlaybackPresentationModule
import com.calypsan.listenup.client.di.jvmSharedModules
import com.calypsan.listenup.client.di.platformModule
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.desktop.di.desktopAppModule
import com.calypsan.listenup.desktop.media.GlobalMediaKeyManager
import com.calypsan.listenup.desktop.window.ListenUpWindow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting ListenUp Desktop..." }

    // Initialize Koin DI
    startKoin {
        modules(
            jvmSharedModules() + // From :shared module
                platformModule + // From :composeApp desktopMain
                desktopPlaybackModule + // PlaybackManager wiring from :app:sharedLogic
                desktopDownloadModule + // DownloadEnqueuer wiring from :app:sharedLogic
                jvmPlaybackPresentationModule() + // Shared playback VM bindings
                desktopAppModule, // Desktop app specific
        )
    }

    // Attach the rotating file sink to the logging tap (the logback ListenUpFileAppender).
    // From here on, every log line — plus the buffered startup lines — is persisted.
    LogSinkRegistry.attach(getKoin().get<FileLogSink>())

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
                getKoin().get<AudioPlayer>().releasePlayer()
                // Final teardown before the process exits: drain and flush the log file.
                // Blocking here is intentional — nothing else runs after this point.
                runBlocking { getKoin().get<FileLogSink>().close() }
                exitApplication()
            },
        )
    }
}
