package com.calypsan.listenup.server

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.di.adminUserRosterModule
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.backupModule
import com.calypsan.listenup.server.di.booksModule
import com.calypsan.listenup.server.di.importModule
import com.calypsan.listenup.server.di.libraryModule
import com.calypsan.listenup.server.di.libraryWriteModule
import com.calypsan.listenup.server.di.organizeModule
import com.calypsan.listenup.server.di.mdnsModule
import com.calypsan.listenup.server.di.metadataModule
import com.calypsan.listenup.server.di.notificationModule
import com.calypsan.listenup.server.di.playbackModule
import com.calypsan.listenup.server.di.profileModule
import com.calypsan.listenup.server.di.publicProfileModule
import com.calypsan.listenup.server.di.pushModule
import com.calypsan.listenup.server.di.scannerModule
import com.calypsan.listenup.server.di.seedModule
import com.calypsan.listenup.server.di.shelfModule
import com.calypsan.listenup.server.di.sidecarModule
import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.di.userPreferencesModule
import com.calypsan.listenup.server.di.uploadModule
import com.calypsan.listenup.server.embeddedmeta.embeddedmetaModule
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installAutoHeadResponse
import com.calypsan.listenup.server.plugins.installCallId
import com.calypsan.listenup.server.plugins.installCallLogging
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
 * Largest **inbound** WebSocket frame the RPC mounts accept.
 *
 * **Inbound only.** Ktor passes `maxFrameSize` to `WebSocketReader` and nowhere else —
 * `WebSocketWriter` takes no size parameter — so this bounds what a peer may send *us*. Server
 * responses are unaffected, and in particular a catch-up sync page (which can run to tens of MB for
 * a large library) is never measured against this number.
 *
 * Inbound is the exposure worth bounding: a frame is buffered in full before any handler runs —
 * including the per-IP throttle in [com.calypsan.listenup.server.auth.AuthServiceImpl] — and
 * `/api/rpc/public` is reachable without a credential. Ktor's default is `Long.MAX_VALUE`, so one
 * anonymous connection could otherwise cost unbounded memory before the throttle got a say.
 *
 * Sized against the largest call a legitimate client can make: `BookService.setBookChapters` at
 * `MAX_CHAPTERS_PER_BOOK` (5000) rows with every [com.calypsan.listenup.api.dto.ChapterInput] field
 * at its contract maximum (`MAX_TITLE` 1024, both `MAX_SECTION_TITLE` 256 headings populated),
 * measured through `contractJson` at **8,258,144 bytes**; ×4 headroom = 33,032,576, rounded up to
 * the next power of two = **32 MiB**. Nothing else on the RPC surface comes close — no binary
 * payload rides this transport at all (uploads are REST multipart), and the same call with
 * real-world chapter titles measures 707,037 bytes.
 *
 * ⚠️ Re-measure if `MAX_CHAPTERS_PER_BOOK` or `ChapterInput`'s length ceilings are raised. Every
 * other list-taking call is now bounded at its own service — `setBookCollections` by
 * `MAX_COLLECTIONS_PER_BOOK` and `reorderShelfBooks` by `MAX_BOOKS_PER_SHELF_REORDER`, both id lists
 * that sit far inside this number even at their ceiling — so this is a transport backstop, not the
 * primary bound on any call.
 */
private const val WS_MAX_FRAME_SIZE_BYTES = 33_554_432L

/**
 * Installs the core Ktor plugins every route depends on (serialization, resources, RPC, ranges, HEAD),
 * and registers the shutdown farewell log.
 */
internal fun Application.installCorePlugins() {
    install(ContentNegotiation) { json(contractJson) }
    install(Resources)
    // First, so every later plugin and every handler sees the caller's real address: the per-IP
    // rate-limit buckets key on `origin.remoteHost`, which behind a proxy is otherwise the proxy.
    // Off unless the operator opts in — see the KDoc for why that default is not negotiable.
    installForwardedHeadersIfTrusted()
    // Keepalive for the kotlinx.rpc WebSockets: server-side pings detect a dead/half-open client
    // socket and close the session, so a stalled RPC call is torn down rather than left hanging.
    // Mirrors the client-side ping in ApiClientFactory. Must precede install(Krpc), which transports
    // its sessions over these WebSockets.
    install(WebSockets) {
        pingPeriodMillis = WS_PING_PERIOD_MS
        timeoutMillis = WS_PING_TIMEOUT_MS
        maxFrameSize = WS_MAX_FRAME_SIZE_BYTES
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
    pushSenderToken: String?,
) {
    // KoinIsolated (not Koin): the DI graph is scoped to THIS Application instance instead of the
    // process-global Koin context. Production runs one Application, so behaviour is unchanged — but
    // it removes the global `on(ApplicationStopped){ stopKoin() }` whose late async firing could rip
    // the live context out of the next test spec (the BookAccessPolicy NoDefinitionFound E2E flake).
    install(KoinIsolated) {
        val modules = mutableListOf(authModule(environment.config, pushRelayUrl, applicationScope, pushSenderToken))
        modules += scannerModule(applicationScope, metadataPrecedence, watchEnabled)
        modules += booksModule(metadataPrecedence, embeddedCoverCacheSize, homeDir)
        modules += metadataModule(homeDir)
        modules += playbackModule(homeDir, applicationScope, environment.config.transcodeSettings())
        modules += libraryModule()
        modules += libraryWriteModule(homeDir)
        modules += sidecarModule(applicationScope)
        modules += organizeModule()
        modules += embeddedmetaModule
        modules += syncModule()
        modules += publicProfileModule()
        modules += adminUserRosterModule()
        modules += shelfModule()
        modules += pushModule()
        modules += notificationModule()
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
        modules += uploadModule(homeDir)
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
 * version-header exchange, and the [com.calypsan.listenup.api.error.AppError] status-page mapper.
 * Sequenced after [install] of Koin in [module] because each reads a Koin-provided collaborator.
 *
 * Rate limiting is NOT installed here — the auth surface (login/register/setupRoot/refresh/...)
 * and the push-test send button throttle through the RPC-side `enforceRate`/`AuthRateBucket`
 * mechanism (`com.calypsan.listenup.server.auth.LoginRateLimiter`) instead, since first-party
 * clients call these over RPC where a Ktor route-level plugin never runs.
 */
internal fun Application.installRequestPipeline() {
    installCallId()
    installCallLogging()
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
