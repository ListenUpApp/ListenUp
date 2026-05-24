package com.calypsan.listenup.server

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.booksModule
import com.calypsan.listenup.server.di.metadataModule
import com.calypsan.listenup.server.di.playbackModule
import com.calypsan.listenup.server.di.scannerModule
import com.calypsan.listenup.server.di.seedModule
import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.embeddedmeta.embeddedmetaModule
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installCallIdAndLogging
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installRateLimiting
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.routes.adminRoutes
import com.calypsan.listenup.server.routes.audioRoutes
import com.calypsan.listenup.server.routes.authRoutes
import com.calypsan.listenup.server.routes.bookRoutes
import com.calypsan.listenup.server.routes.contributorRoutes
import com.calypsan.listenup.server.routes.healthRoutes
import com.calypsan.listenup.server.routes.metadataImageRoutes
import com.calypsan.listenup.server.routes.metadataRoutes
import com.calypsan.listenup.server.routes.instanceRoutes
import com.calypsan.listenup.server.routes.playbackRoutes
import com.calypsan.listenup.server.routes.rpcRoutes
import com.calypsan.listenup.server.routes.scannerRoutes
import com.calypsan.listenup.server.routes.searchRoutes
import com.calypsan.listenup.server.routes.seriesRoutes
import com.calypsan.listenup.server.routes.sseRoutes
import com.calypsan.listenup.server.sync.syncRoutes
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.UserStatsBackfillService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.EngineMain
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
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

private const val SEED_PROFILE_DEMO = "demo"

private const val DEFAULT_EMBEDDED_COVER_CACHE_SIZE = 1000

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    install(ContentNegotiation) { json(contractJson) }
    install(Resources)
    install(SSE)
    install(Krpc)
    install(PartialContent)
    install(AutoHeadResponse)

    val seedProfile = resolveSeedProfile()
    val applicationScope = CoroutineScope(coroutineContext + SupervisorJob())
    val resolvedLibraryPath = resolveLibraryPath() ?: resolveDemoLibraryFallback(seedProfile)
    val metadataPrecedence = resolveMetadataPrecedence()
    val embeddedCoverCacheSize = resolveEmbeddedCoverCacheSize()

    install(Koin) {
        val modules = mutableListOf(authModule(environment.config))
        if (resolvedLibraryPath != null) {
            modules += scannerModule(resolvedLibraryPath, applicationScope, metadataPrecedence)
            modules += booksModule(resolvedLibraryPath, metadataPrecedence, embeddedCoverCacheSize)
            modules += metadataModule(kotlinx.io.files.Path(resolvedLibraryPath.toString()))
            modules += playbackModule()
        }
        modules += embeddedmetaModule
        modules += syncModule()
        if (seedProfile == SEED_PROFILE_DEMO) {
            modules += seedModule(
                hasPlaybackModule = resolvedLibraryPath != null,
                hasBooksModule = resolvedLibraryPath != null,
            )
        }
        modules(modules)
    }

    if (seedProfile == SEED_PROFILE_DEMO) {
        val seedRunner by inject<SeedRunner>()
        applicationScope.launch {
            runCatching { seedRunner.run() }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logger.error(e) { "demo seeding failed — server keeps running" }
                }
        }
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
    val bookService: BookService? = resolvedLibraryPath?.let { inject<BookService>().value }
    val contributorService: ContributorService? = resolvedLibraryPath?.let { inject<ContributorService>().value }
    val seriesService: SeriesService? = resolvedLibraryPath?.let { inject<SeriesService>().value }
    val coverResponder: CoverResponder? = resolvedLibraryPath?.let { inject<CoverResponder>().value }
    val playbackService: PlaybackService? = resolvedLibraryPath?.let { inject<PlaybackService>().value }
    val backfillService: UserStatsBackfillService? =
        resolvedLibraryPath?.let {
            inject<UserStatsBackfillService>().value
        }
    val audioFileLocator: AudioFileLocator? = resolvedLibraryPath?.let { inject<AudioFileLocator>().value }
    val audioUrlSigner: AudioUrlSigner? = resolvedLibraryPath?.let { inject<AudioUrlSigner>().value }
    val contributorRepository: ContributorRepository? =
        resolvedLibraryPath?.let {
            inject<ContributorRepository>().value
        }
    val seriesRepository: SeriesRepository? = resolvedLibraryPath?.let { inject<SeriesRepository>().value }
    val metadataLookupService: MetadataLookupService? =
        resolvedLibraryPath?.let { inject<MetadataLookupService>().value }
    val searchService: SearchService? = resolvedLibraryPath?.let { inject<SearchService>().value }

    routing {
        healthRoutes()
        instanceRoutes()
        sseRoutes()
        authRoutes(authService)
        rpcRoutes(
            authService,
            scannerService,
            bookService,
            contributorService,
            seriesService,
            playbackService,
            metadataLookupService,
            searchService,
        )
        authenticate(JWT_PROVIDER) {
            syncRoutes()
            if (bookService != null && coverResponder != null) bookRoutes(bookService, coverResponder)
            if (contributorService != null) contributorRoutes(contributorService)
            if (seriesService != null) seriesRoutes(seriesService)
            if (playbackService != null) playbackRoutes(playbackService)
            if (backfillService != null) adminRoutes(backfillService)
            if (contributorRepository != null && seriesRepository != null) {
                metadataImageRoutes(contributorRepository, seriesRepository, resolvedLibraryPath!!)
            }
            if (metadataLookupService != null) metadataRoutes(metadataLookupService)
            if (searchService != null) searchRoutes(searchService)
        }
        if (scannerService != null && eventBus != null) {
            scannerRoutes(scannerService, eventBus)
        }
        if (audioFileLocator != null && audioUrlSigner != null) {
            audioRoutes(audioFileLocator, audioUrlSigner)
        }
    }

    if (resolvedLibraryPath != null) {
        bootstrapScannerOnStartup(applicationScope)
        val cleanupTask by inject<ActiveSessionCleanupTask>()
        cleanupTask.start(applicationScope)
        val metadataCacheCleanupTask by inject<MetadataCacheCleanupTask>()
        metadataCacheCleanupTask.start(applicationScope)
        val orphanImageCleanupTask by inject<OrphanImageCleanupTask>()
        orphanImageCleanupTask.start(applicationScope)
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
 * Reads `scanner.metadataPrecedence` from configuration and parses it into a
 * [MetadataPrecedence]. A blank value yields [MetadataPrecedence.DEFAULT].
 *
 * An invalid token throws [IllegalArgumentException] — deliberately left to
 * propagate so a misconfigured precedence fails server startup loud rather
 * than silently scanning with the default order.
 */
private fun Application.resolveMetadataPrecedence(): MetadataPrecedence {
    val raw =
        environment.config
            .propertyOrNull("scanner.metadataPrecedence")
            ?.getString()
            .orEmpty()
    return MetadataPrecedence.parse(raw)
}

/**
 * Reads `scanner.embeddedCoverCacheSize` from configuration. Falls back to
 * [DEFAULT_EMBEDDED_COVER_CACHE_SIZE] when unset or blank.
 *
 * A non-numeric value throws [NumberFormatException] — deliberately left to
 * propagate so a misconfigured cache size fails server startup loud rather than
 * silently running with the default.
 */
private fun Application.resolveEmbeddedCoverCacheSize(): Int {
    val raw =
        environment.config
            .propertyOrNull("scanner.embeddedCoverCacheSize")
            ?.getString()
            ?.trim()
            .orEmpty()
    if (raw.isBlank()) return DEFAULT_EMBEDDED_COVER_CACHE_SIZE
    return raw.toInt()
}

/**
 * Reads `seed.profile` from configuration. Returns the trimmed value, or null when
 * unset/blank. Only `"demo"` is recognized today; an unrecognized non-blank value is
 * returned as null and logged as ignored.
 */
private fun Application.resolveSeedProfile(): String? {
    val raw =
        environment.config
            .propertyOrNull("seed.profile")
            ?.getString()
            ?.trim()
            .orEmpty()
    if (raw.isBlank()) return null
    if (raw != SEED_PROFILE_DEMO) {
        logger.warn { "seed.profile '$raw' is not a recognized profile — ignoring" }
        return null
    }
    return raw
}

/**
 * When the demo seed profile is active and no explicit `scanner.libraryPath` is set, fall back to
 * the generated synthetic library at `build/seed-library` (produced by `:server:generateSeedLibrary`).
 * Returns null — with a guiding log line — if that directory does not exist yet.
 */
private fun Application.resolveDemoLibraryFallback(seedProfile: String?): Path? {
    if (seedProfile != SEED_PROFILE_DEMO) return null
    val candidate = Path.of("build", "seed-library")
    if (!Files.isDirectory(candidate)) {
        logger.warn {
            "seed.profile=demo but no synthetic library at '$candidate' — run " +
                "':server:generateSeedLibrary' (or use ':server:runDemo'). The demo user is still " +
                "seeded; the library will be empty until then."
        }
        return null
    }
    logger.info { "seed.profile=demo — scanning the generated synthetic library at '$candidate'" }
    return candidate
}

/**
 * Kicks off the initial scan and starts the [FolderWatcher] and
 * [BookPersister]. Runs on the supplied [scope] so cancellation flows through
 * structured concurrency when the application shuts down.
 *
 * The [BookPersister] collector is started *before* the initial full scan is
 * launched so the persister has subscribed to the `scanResultBus` by the time
 * the first [com.calypsan.listenup.api.dto.scanner.ScanResult] is emitted. The
 * bus replays its last value, so a late subscriber would still catch up — but
 * starting first avoids relying on replay timing.
 *
 * The initial scan failures don't bubble — we log and continue. The user
 * can re-trigger via the scanner RPC surface.
 */
private fun Application.bootstrapScannerOnStartup(scope: CoroutineScope) {
    val scanner by inject<Scanner>()
    val watcher by inject<FolderWatcher>()
    val bookPersister by inject<BookPersister>()

    bookPersister.start()

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
