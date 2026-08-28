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
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.desktop.di.desktopAppModule
import com.calypsan.listenup.desktop.media.GlobalMediaKeyManager
import com.calypsan.listenup.desktop.window.ListenUpWindow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import kotlin.time.Duration.Companion.seconds

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

    // Device-local preferences sit behind a suspend read, so they arrive as a boot step or not at
    // all — the StateFlows start on hard-coded defaults and nothing else ever replaces them.
    // ListenUpWindow themes itself from themeMode during composition, so this has to complete
    // before the first frame or the window paints the wrong theme and corrects it in view of the
    // user. Blocking here is deliberate and bounded: a handful of storage reads on a cold process
    // with no UI yet to keep responsive — the same ordering MainActivity holds behind its splash.
    runBlocking { getKoin().get<LocalPreferences>().initializeLocalPreferences() }

    // One-time cleanup of the pre-sink log location: logback.xml used to roll files under
    // ~/.listenup/logs before file persistence moved to FileLogSink in the XDG data dir.
    // Best-effort delete of exactly that directory — it only ever held bounded,
    // app-generated log data now superseded by the sink.
    File(System.getProperty("user.home"), ".listenup/logs")
        .takeIf { it.isDirectory }
        ?.deleteRecursively()

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
                // Blocking here is intentional — nothing else runs after this point — but
                // bounded: if the drain stalls (e.g. a wedged disk), exit anyway. The loss
                // is one best-effort batch, consistent with the sink's failure policy.
                runBlocking { withTimeoutOrNull(2.seconds) { getKoin().get<FileLogSink>().close() } }
                exitApplication()
            },
        )
    }
}
