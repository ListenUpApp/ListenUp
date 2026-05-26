package com.calypsan.listenup.server

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.booksModule
import com.calypsan.listenup.server.di.libraryModule
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
import com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.routes.adminRoutes
import com.calypsan.listenup.server.routes.audioRoutes
import com.calypsan.listenup.server.routes.authRoutes
import com.calypsan.listenup.server.routes.bookRoutes
import com.calypsan.listenup.server.routes.contributorRoutes
import com.calypsan.listenup.server.routes.healthRoutes
import com.calypsan.listenup.server.routes.libraryAdminRoutes
import com.calypsan.listenup.server.routes.metadataImageRoutes
import com.calypsan.listenup.server.routes.metadataRoutes
import com.calypsan.listenup.server.routes.instanceRoutes
import com.calypsan.listenup.server.routes.playbackProgressRoutes
import com.calypsan.listenup.server.routes.playbackRoutes
import com.calypsan.listenup.server.routes.rpcRoutes
import com.calypsan.listenup.server.routes.scannerRoutes
import com.calypsan.listenup.server.routes.searchRoutes
import com.calypsan.listenup.server.routes.seriesRoutes
import com.calypsan.listenup.server.routes.sseRoutes
import com.calypsan.listenup.server.routes.tagRoutes
import com.calypsan.listenup.server.sync.syncRoutes
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
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
            modules += libraryModule()
        }
        modules += embeddedmetaModule
        modules += syncModule()
        if (seedProfile == SEED_PROFILE_DEMO) {
            modules +=
                seedModule(
                    hasPlaybackModule = resolvedLibraryPath != null,
                    hasBooksModule = resolvedLibraryPath != null,
                    demoLibraryPath = resolvedLibraryPath?.toString(),
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
    val playbackProgressService: PlaybackProgressService? =
        resolvedLibraryPath?.let { inject<PlaybackProgressService>().value }
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
    val libraryAdminService: LibraryAdminService? =
        resolvedLibraryPath?.let { inject<LibraryAdminService>().value }
    val tagService: TagService? = resolvedLibraryPath?.let { inject<TagService>().value }

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
            playbackProgressService,
            metadataLookupService,
            searchService,
            libraryAdminService,
            tagService,
        )
        authenticate(JWT_PROVIDER) {
            syncRoutes()
            if (libraryAdminService != null) libraryAdminRoutes(libraryAdminService)
            if (bookService != null && coverResponder != null) bookRoutes(bookService, coverResponder)
            if (contributorService != null) contributorRoutes(contributorService)
            if (seriesService != null) seriesRoutes(seriesService)
            if (playbackService != null) playbackRoutes(playbackService)
            if (playbackProgressService != null) playbackProgressRoutes(playbackProgressService)
            if (backfillService != null) adminRoutes(backfillService)
            if (contributorRepository != null && seriesRepository != null) {
                metadataImageRoutes(contributorRepository, seriesRepository, resolvedLibraryPath!!)
            }
            if (metadataLookupService != null) metadataRoutes(metadataLookupService)
            if (searchService != null) searchRoutes(searchService)
            if (tagService != null) tagRoutes(tagService)
        }
        if (scannerService != null && eventBus != null) {
            scannerRoutes(scannerService, eventBus)
        }
        if (audioFileLocator != null && audioUrlSigner != null) {
            audioRoutes(audioFileLocator, audioUrlSigner)
        }
    }

    startBackgroundTasks(applicationScope, resolvedLibraryPath)
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
 * Starts all background scheduler tasks. Session cleanup runs unconditionally;
 * scanner-dependent cleanup tasks and library bootstrap are gated on
 * [libraryPath] because those Koin modules are only loaded when a library path
 * is configured — injecting them without the module would throw [NoDefinitionFoundException].
 *
 * [bootstrapLibraries] is launched in the background — callers should not
 * assume it has completed when this function returns.
 */
private fun Application.startBackgroundTasks(
    scope: CoroutineScope,
    libraryPath: Path?,
) {
    // Session cleanup runs unconditionally — sessions exist regardless of library config.
    inject<ExpiredSessionCleanupTask>().value.start(scope)

    if (libraryPath != null) {
        val orchestrator by inject<ScanOrchestrator>()
        val bookPersister by inject<BookPersister>()
        val libraryAdminService by inject<LibraryAdminService>()

        // BookPersister must be started before the first scan result can arrive so it
        // is subscribed to the scanResultBus before any ScanResult is emitted.
        bookPersister.start()

        val cleanupTask by inject<ActiveSessionCleanupTask>()
        cleanupTask.start(scope)
        val metadataCacheCleanupTask by inject<MetadataCacheCleanupTask>()
        metadataCacheCleanupTask.start(scope)
        val orphanImageCleanupTask by inject<OrphanImageCleanupTask>()
        orphanImageCleanupTask.start(scope)

        scope.launch {
            runCatching {
                bootstrapLibraries(
                    libraryAdminService = libraryAdminService,
                    scanOrchestrator = orchestrator,
                    libraryPath = libraryPath.toString(),
                )
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.error(e) { "library bootstrap failed — server keeps running" }
            }
        }
    } else {
        logger.warn {
            "scanner.libraryPath unset or invalid — server starts without scanning. " +
                "Set LISTENUP_LIBRARY_PATH to enable."
        }
    }
}

/**
 * One-shot library bootstrap called at server startup and in [ApplicationBootstrapTest].
 *
 * Rules (Task 18):
 *  - If libraries already exist → register each with [scanOrchestrator] via
 *    [ScanOrchestrator.onLibraryAdded] so the watcher and scanner bundle are
 *    warmed up. No scan is triggered.
 *  - If no libraries exist and [libraryPath] is non-null → create a single
 *    default "My Library" pointing at that path. [LibraryAdminServiceImpl.createLibrary]
 *    internally calls [ScanOrchestrator.onLibraryAdded], so we don't need to.
 *    The `runCatching` guard handles the narrow race where a concurrent caller
 *    (e.g. a test fixture seeding the DB) inserts the same root_path between
 *    our `listLibraries` check and the `createLibrary` insert. On any error,
 *    we re-read the table and call `onLibraryAdded` for whatever ended up there.
 *  - If no libraries exist and [libraryPath] is null → log and return; the
 *    operator must create a library via the LibraryAdmin REST surface.
 *
 * **No auto-scan is ever triggered.** Scans are user-initiated (POST /scan) or
 * file-watcher-triggered. This keeps startup fast and avoids racing against
 * test fixtures that insert their own library rows.
 */
internal suspend fun bootstrapLibraries(
    libraryAdminService: LibraryAdminService,
    scanOrchestrator: ScanOrchestrator,
    libraryPath: String?,
) {
    val existingResult = libraryAdminService.listLibraries()
    if (existingResult is AppResult.Failure) {
        logger.warn { "bootstrap: could not list libraries — ${existingResult.error.message}" }
        return
    }
    val existing = (existingResult as AppResult.Success).data

    when {
        existing.isNotEmpty() -> {
            logger.info { "bootstrap: ${existing.size} library(s) already configured; env var ignored" }
            existing.forEach { library -> scanOrchestrator.onLibraryAdded(library) }
        }

        libraryPath != null -> {
            bootstrapCreateDefaultLibrary(libraryAdminService, scanOrchestrator, libraryPath)
        }

        else -> {
            logger.info {
                "bootstrap: no libraries and no env var; awaiting client onboarding via LibraryAdminService.createLibrary"
            }
        }
    }
}

/**
 * Creates the "My Library" default library from [libraryPath] and registers it with
 * the [scanOrchestrator]. A `runCatching` guard handles the narrow race where a
 * concurrent caller inserts the same root path between the `listLibraries` check and
 * this insert — on any error the DB is re-read and whatever ended up there is
 * registered instead.
 */
private suspend fun bootstrapCreateDefaultLibrary(
    libraryAdminService: LibraryAdminService,
    scanOrchestrator: ScanOrchestrator,
    libraryPath: String,
) {
    logger.info { "bootstrap: no libraries configured; creating default from env var path=$libraryPath" }
    val created =
        runCatching {
            libraryAdminService.createLibrary(
                CreateLibraryRequest(name = "My Library", folderPaths = listOf(libraryPath)),
            )
        }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }
            .getOrNull()
    if (created is AppResult.Success) {
        logger.info { "bootstrap: default library created id=${created.data.id.value}" }
    } else {
        // Either createLibrary returned Failure or threw — re-check the DB.
        // A concurrent insert (test fixture race) may have already added a library.
        if (created is AppResult.Failure) {
            logger.warn { "bootstrap: createLibrary returned ${created.error.code} — re-checking" }
        } else {
            logger.warn { "bootstrap: createLibrary threw — re-checking for concurrent inserts" }
        }
        val recheck = libraryAdminService.listLibraries()
        if (recheck is AppResult.Success && recheck.data.isNotEmpty()) {
            logger.info {
                "bootstrap: found ${recheck.data.size} library(s) after re-check; registering with orchestrator"
            }
            recheck.data.forEach { library -> scanOrchestrator.onLibraryAdded(library) }
        }
    }
}
