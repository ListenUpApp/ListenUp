package com.calypsan.listenup.server

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.di.adminUserRosterModule
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.backupModule
import com.calypsan.listenup.server.di.booksModule
import com.calypsan.listenup.server.di.campfireModule
import com.calypsan.listenup.server.di.entityModule
import com.calypsan.listenup.server.di.importModule
import com.calypsan.listenup.server.di.libraryModule
import com.calypsan.listenup.server.di.libraryWriteModule
import com.calypsan.listenup.server.di.organizeModule
import com.calypsan.listenup.server.di.mdnsModule
import com.calypsan.listenup.server.di.metadataModule
import com.calypsan.listenup.server.di.playbackModule
import com.calypsan.listenup.server.di.profileModule
import com.calypsan.listenup.server.di.publicProfileModule
import com.calypsan.listenup.server.di.pushModule
import com.calypsan.listenup.server.di.readingOrderModule
import com.calypsan.listenup.server.di.scannerModule
import com.calypsan.listenup.server.di.seedModule
import com.calypsan.listenup.server.di.shelfModule
import com.calypsan.listenup.server.di.sidecarModule
import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.di.userPreferencesModule
import com.calypsan.listenup.server.embeddedmeta.embeddedmetaModule
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installAutoHeadResponse
import com.calypsan.listenup.server.plugins.installCallId
import com.calypsan.listenup.server.plugins.installCallLogging
import com.calypsan.listenup.server.plugins.installRateLimiting
import com.calypsan.listenup.server.plugins.installVersionHeaders
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.resources.Resources
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.rpc.krpc.ktor.server.Krpc
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.plugin.KoinIsolated

/** RPC WebSocket keepalive (ms): server pings every [WS_PING_PERIOD_MS]; closes if no pong within [WS_PING_TIMEOUT_MS]. */
private const val WS_PING_PERIOD_MS = 15_000L
private const val WS_PING_TIMEOUT_MS = 15_000L

/**
 * Installs the core Ktor plugins every route depends on (serialization, resources, SSE, RPC, ranges, HEAD),
 * and registers the shutdown farewell log.
 */
internal fun Application.installCorePlugins() {
    install(ContentNegotiation) { json(contractJson) }
    install(Resources)
    install(SSE)
    // Keepalive for the kotlinx.rpc WebSockets: server-side pings detect a dead/half-open client
    // socket and close the session, so a stalled RPC call is torn down rather than left hanging.
    // Mirrors the client-side ping in ApiClientFactory. Must precede install(Krpc), which transports
    // its sessions over these WebSockets.
    install(WebSockets) {
        pingPeriodMillis = WS_PING_PERIOD_MS
        timeoutMillis = WS_PING_TIMEOUT_MS
    }
    install(Krpc)
    install(PartialContent)
    installAutoHeadResponse()
    monitor.subscribe(ApplicationStopped) { logger.info { "See You Space Cowboy..." } }
}

/**
 * Installs Koin with the assembled module set. Every domain slice — auth, scanner, books,
 * metadata, playback, library, embedded-metadata, and sync — loads unconditionally so a
 * library-less boot can onboard a library at runtime without a restart. The seed module loads
 * only in the demo profile.
 */
internal fun Application.installDependencies(
    seedProfile: String?,
    applicationScope: CoroutineScope,
    homeDir: Path,
    metadataPrecedence: MetadataPrecedence,
    embeddedCoverCacheSize: Int,
    watchEnabled: Boolean,
    pushRelayUrl: String,
) {
    // KoinIsolated (not Koin): the DI graph is scoped to THIS Application instance instead of the
    // process-global Koin context. Production runs one Application, so behaviour is unchanged — but
    // it removes the global `on(ApplicationStopped){ stopKoin() }` whose late async firing could rip
    // the live context out of the next test spec (the BookAccessPolicy NoDefinitionFound E2E flake).
    install(KoinIsolated) {
        val modules = mutableListOf(authModule(environment.config, pushRelayUrl))
        modules += scannerModule(applicationScope, metadataPrecedence, watchEnabled)
        modules += booksModule(metadataPrecedence, embeddedCoverCacheSize, homeDir)
        modules += metadataModule(homeDir)
        modules += playbackModule()
        modules += libraryModule()
        modules += libraryWriteModule(homeDir)
        modules += organizeModule()
        modules += sidecarModule(applicationScope)
        modules += embeddedmetaModule
        modules += syncModule()
        modules += publicProfileModule()
        modules += adminUserRosterModule()
        modules += shelfModule()
        modules += readingOrderModule()
        modules += entityModule()
        modules += pushModule()
        modules += campfireModule()
        val httpPort =
            environment.config
                .propertyOrNull("ktor.deployment.port")
                ?.getString()
                ?.toIntOrNull() ?: 8080
        modules += mdnsModule(applicationScope, httpPort)
        modules += profileModule(Path(homeDir, "avatars"))
        modules += userPreferencesModule()
        modules += backupModule(homeDir)
        modules += importModule(homeDir)
        if (seedProfile == SEED_PROFILE_DEMO) {
            modules +=
                seedModule(
                    hasPlaybackModule = true,
                    hasBooksModule = true,
                    hasGenresModule = true,
                    hasMoodsModule = true,
                    hasCollectionsModule = true,
                    hasShelvesModule = true,
                )
        }
        modules(modules)
    }
}

/**
 * Installs the request-pipeline plugins that depend on Koin being wired: correlation-id + logging,
 * rate limiting, version-header exchange, and the [com.calypsan.listenup.api.error.AppError]
 * status-page mapper. Sequenced after [install] of Koin in [module] because each reads a
 * Koin-provided collaborator.
 */
internal fun Application.installRequestPipeline() {
    installCallId()
    installCallLogging()
    installRateLimiting()
    installVersionHeaders()
    installAppErrorStatusPages()
}

/**
 * Releases every background resource when the application stops — real shutdown and each
 * `testApplication` teardown. Order: unmount watchers (close native FS handles), cancel the
 * background-task [applicationScope] (cleanup loops, the BookPersister scan-result collector,
 * bootstrap), then close the database handle (SQLDelight driver + migration data source). Each step
 * is best-effort and CANCELS rather than joins: joining the watcher's blocking read would time out
 * Ktor's application disposal. Ktor drains in-flight requests before firing [ApplicationStopped], so
 * nothing is using the database.
 */
internal fun Application.installGracefulShutdown(applicationScope: CoroutineScope) {
    // Resolve eagerly while Koin is still open — by ApplicationStopped the Koin scope is
    // already closed, so a lazy `by inject` access inside the handler would throw
    // ClosedScopeException. Capture the live references in the closure instead.
    val watcherSupervisor = koinGet<WatcherSupervisorPort>()
    val databaseHandle = koinGet<DatabaseHandle>()
    monitor.subscribe(ApplicationStopped) {
        // Best-effort, sequential shutdown (real stop + every testApplication teardown). Each step
        // re-throws CancellationException via logShutdownFailure (honest-over-silent) — see its doc.
        runCatching { runBlocking { watcherSupervisor.unmountAll() } }
            .onFailure { logShutdownFailure(it, "watcher unmount on shutdown failed") }
        runCatching { applicationScope.cancel("application stopped") }
            .onFailure { logShutdownFailure(it, "background-scope cancel on shutdown failed") }
        runCatching { databaseHandle.close() }
            .onFailure { logShutdownFailure(it, "db pool close on shutdown failed") }
    }
}

/**
 * Logs a best-effort shutdown-step failure, but re-throws `CancellationException` (honest-over-silent).
 * A cancellation reaching the synchronous [ApplicationStopped] callback means the process/test is being
 * torn down, so aborting the remaining best-effort steps is correct.
 */
private fun logShutdownFailure(
    throwable: Throwable,
    message: String,
) {
    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
    logger.warn(throwable) { message }
}
