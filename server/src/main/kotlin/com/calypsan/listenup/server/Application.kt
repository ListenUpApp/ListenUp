package com.calypsan.listenup.server

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.scannerModule
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installCallIdAndLogging
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installRateLimiting
import com.calypsan.listenup.server.routes.authRoutes
import com.calypsan.listenup.server.routes.healthRoutes
import com.calypsan.listenup.server.routes.instanceRoutes
import com.calypsan.listenup.server.routes.rpcRoutes
import com.calypsan.listenup.server.routes.scannerRoutes
import com.calypsan.listenup.server.routes.sseRoutes
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.ktor.server.Krpc
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    install(ContentNegotiation) { json(contractJson) }
    install(Resources)
    install(SSE)
    install(Krpc)

    val resolvedLibraryPath = resolveLibraryPath()
    val applicationScope = CoroutineScope(coroutineContext + SupervisorJob())

    install(Koin) {
        val modules = mutableListOf(authModule(environment.config))
        if (resolvedLibraryPath != null) {
            modules += scannerModule(resolvedLibraryPath, applicationScope)
        }
        modules(modules)
    }

    installCallIdAndLogging()
    installRateLimiting()
    installAppErrorStatusPages()

    val jwt by inject<JwtConfiguration>()
    val sessions by inject<SessionService>()
    val authService by inject<AuthServiceImpl>()

    installJwtAuth(jwt, sessions)

    val scannerService: ScannerService? = resolvedLibraryPath?.let { inject<ScannerService>().value }
    val eventBus: SharedFlow<ScanEvent>? = resolvedLibraryPath?.let { inject<SharedFlow<ScanEvent>>().value }

    routing {
        healthRoutes()
        instanceRoutes()
        sseRoutes()
        authRoutes(authService)
        rpcRoutes(authService, scannerService)
        if (scannerService != null && eventBus != null) {
            scannerRoutes(scannerService, eventBus)
        }
    }

    if (resolvedLibraryPath != null) {
        bootstrapScannerOnStartup(applicationScope)
    } else {
        logger.warn {
            "scanner.libraryPath unset or invalid — server starts without scanning. " +
                "Set LISTENUP_LIBRARY_PATH to enable."
        }
    }
}

/**
 * Reads `scanner.libraryPath` from configuration. Returns null when unset,
 * blank, or pointing at a non-directory — the server still starts in those
 * cases, just without an active scanner.
 */
private fun Application.resolveLibraryPath(): Path? {
    val raw =
        environment.config
            .propertyOrNull("scanner.libraryPath")
            ?.getString()
            .orEmpty()
    if (raw.isBlank()) return null
    val path = Path.of(raw)
    if (!Files.isDirectory(path)) {
        logger.warn { "scanner.libraryPath '$raw' does not point to a directory — scanner disabled" }
        return null
    }
    return path
}

/**
 * Kicks off the initial scan and starts the [FolderWatcher]. Runs on the
 * supplied [scope] so cancellation flows through structured concurrency
 * when the application shuts down.
 *
 * The initial scan failures don't bubble — we log and continue. The user
 * can re-trigger via the scanner RPC surface.
 */
private fun Application.bootstrapScannerOnStartup(scope: CoroutineScope) {
    val scanner by inject<Scanner>()
    val watcher by inject<FolderWatcher>()

    scope.launch {
        runCatching { scanner.runFullScan() }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.error(e) { "initial scan failed — server keeps running" }
            }
    }

    scope.launch {
        runCatching { watcher.start() }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.error(e) { "folder watcher failed to start — real-time updates disabled" }
            }
    }
}
