package com.calypsan.listenup.server

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.di.authModule
import com.calypsan.listenup.server.di.backupModule
import com.calypsan.listenup.server.di.booksModule
import com.calypsan.listenup.server.di.shelfModule
import com.calypsan.listenup.server.di.importModule
import com.calypsan.listenup.server.di.libraryModule
import com.calypsan.listenup.server.di.mdnsModule
import com.calypsan.listenup.server.di.metadataModule
import com.calypsan.listenup.server.di.playbackModule
import com.calypsan.listenup.server.di.scannerModule
import com.calypsan.listenup.server.di.seedModule
import com.calypsan.listenup.server.di.profileModule
import com.calypsan.listenup.server.di.publicProfileModule
import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.embeddedmeta.embeddedmetaModule
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.mdns.launchMdnsRefreshOnServerInfoChange
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import com.calypsan.listenup.server.plugins.installCallIdAndLogging
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installRateLimiting
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.plugins.installMaintenanceGate
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask
import com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.routes.adminRoutes
import com.calypsan.listenup.server.routes.adminUserRoutes
import com.calypsan.listenup.server.routes.audioRoutes
import com.calypsan.listenup.server.routes.authRoutes
import com.calypsan.listenup.server.routes.backupRoutes
import com.calypsan.listenup.server.routes.importRoutes
import com.calypsan.listenup.server.routes.bookRoutes
import com.calypsan.listenup.server.routes.collectionAdminRoutes
import com.calypsan.listenup.server.routes.collectionRoutes
import com.calypsan.listenup.server.routes.contributorRoutes
import com.calypsan.listenup.server.routes.healthRoutes
import com.calypsan.listenup.server.routes.libraryAdminRoutes
import com.calypsan.listenup.server.routes.metadataImageRoutes
import com.calypsan.listenup.server.routes.metadataRoutes
import com.calypsan.listenup.server.routes.adminInviteRoutes
import com.calypsan.listenup.server.routes.instanceRoutes
import com.calypsan.listenup.server.routes.publicInviteRoutes
import com.calypsan.listenup.server.routes.playbackProgressRoutes
import com.calypsan.listenup.server.routes.playbackRoutes
import com.calypsan.listenup.server.routes.registrationStatusRoutes
import com.calypsan.listenup.server.routes.rpcRoutes
import com.calypsan.listenup.server.routes.profileRoutes
import com.calypsan.listenup.server.routes.scannerRoutes
import com.calypsan.listenup.server.routes.searchRoutes
import com.calypsan.listenup.server.routes.seriesRoutes
import com.calypsan.listenup.server.routes.sseRoutes
import com.calypsan.listenup.server.routes.genreRoutes
import com.calypsan.listenup.server.routes.tagRoutes
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.syncRoutes
import org.jetbrains.exposed.v1.jdbc.Database
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.db.resolveListenupHome
import com.calypsan.listenup.server.scanner.RescanScheduler
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.db.PublicProfilesTable
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.UserStatsBackfillService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlinx.rpc.krpc.ktor.server.Krpc
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private const val SEED_PROFILE_DEMO = "demo"

private const val DEFAULT_EMBEDDED_COVER_CACHE_SIZE = 1000

/**
 * One-time startup backfill for the `public_profiles` projection.
 *
 * Pre-existing users (created before the V31 migration that added the table) have
 * no projection row until something refreshes them. This call populates them once,
 * guarded by an emptiness check so subsequent boots skip it instantly. Must run
 * after Flyway migrations (so `public_profiles` exists) and after Koin starts (so
 * [PublicProfileMaintainer] is resolvable) — both are guaranteed by calling this
 * from [module] after [installDependencies].
 *
 * Runs synchronously on the module init thread via [runBlocking], matching the idiom
 * used by the genre-taxonomy seeder in [launchSeeders].
 */
private fun Application.backfillPublicProfiles() {
    val db by inject<Database>()
    val maintainer by inject<PublicProfileMaintainer>()
    runBlocking {
        runCatching {
            val isEmpty =
                suspendTransaction(db) {
                    PublicProfilesTable.selectAll().limit(1).empty()
                }
            if (isEmpty) maintainer.backfillAll()
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.error(e) { "public_profiles startup backfill failed — projection will self-heal on next refresh" }
        }
    }
}

/**
 * Kicks off seed jobs after Koin is installed. In demo profile we run the full
 * [SeedRunner] (curated demo users + library + tags + genres + …). In any other
 * profile, we still seed the default Genre taxonomy on fresh installs because
 * the genre tree is the curator's starting point, not demo content — the
 * seeder's `isAlreadySeeded` guard keeps subsequent runs no-ops.
 */
private fun Application.launchSeeders(
    scope: CoroutineScope,
    seedProfile: String?,
    libraryConfigured: Boolean,
) {
    if (seedProfile == SEED_PROFILE_DEMO) {
        val seedRunner by inject<SeedRunner>()
        scope.launch {
            runCatching { seedRunner.run() }
                .onFailure { it.logUnlessCancelled("demo seeding failed — server keeps running") }
        }
    } else if (libraryConfigured) {
        val genreSeeder by inject<com.calypsan.listenup.server.seed.GenreDomainSeeder>()
        val moodSeeder by inject<com.calypsan.listenup.server.seed.MoodDomainSeeder>()
        val pendingGenrePromotion by inject<com.calypsan.listenup.server.services.PendingGenrePromotion>()
        // Synchronous on the module init thread — `module()` returns only after the
        // default taxonomy is in place. Pays the cost (~50-100ms of SQLite writes) once
        // on first install; subsequent boots are a single `count()` query via
        // `isAlreadySeeded`. The async-launch alternative leaked seed coroutines past
        // test boundaries on CI, racing scanner-test bootstrap scans.
        kotlinx.coroutines.runBlocking {
            runCatching {
                if (!genreSeeder.isAlreadySeeded()) genreSeeder.seed()
            }.onFailure { it.logUnlessCancelled("genre default-taxonomy seeding failed — server keeps running") }
            // Seed the canonical Audible mood vocabulary on fresh installs (curator
            // dedupe anchors, not demo content). Idempotent via `isAlreadySeeded`.
            runCatching {
                if (!moodSeeder.isAlreadySeeded()) moodSeeder.seed()
            }.onFailure { it.logUnlessCancelled("mood vocabulary seeding failed — server keeps running") }
            // One-time: drain the legacy pending-genre backlog into live genres so an
            // existing library lights up. Runs after seeding (resolution prefers the
            // seeded taxonomy before auto-creating). Idempotent — a drained queue makes
            // subsequent boots a single empty-queue query.
            runCatching { pendingGenrePromotion.run() }
                .onFailure { it.logUnlessCancelled("pending-genre backlog promotion failed — server keeps running") }
        }
    }
}

/**
 * Re-raises [CancellationException] (so structured concurrency stays intact) and
 * logs every other throwable at error level under [message]. The shared tail for
 * the boot-time seed/promotion jobs, which must never bring the server down.
 */
private fun Throwable.logUnlessCancelled(message: String) {
    if (this is kotlinx.coroutines.CancellationException) throw this
    logger.error(this) { message }
}

/**
 * Installs the core Ktor plugins every route depends on (serialization, resources, SSE, RPC, ranges, HEAD),
 * and registers the shutdown farewell log.
 */
private fun Application.installCorePlugins() {
    install(ContentNegotiation) { json(contractJson) }
    install(Resources)
    install(SSE)
    install(Krpc)
    install(PartialContent)
    install(AutoHeadResponse)
    monitor.subscribe(ApplicationStopped) { logger.info { "See You Space Cowboy..." } }
}

/**
 * Installs Koin with the assembled module set. Every domain slice — auth, scanner, books,
 * metadata, playback, library, embedded-metadata, and sync — loads unconditionally so a
 * library-less boot can onboard a library at runtime without a restart. The seed module loads
 * only in the demo profile; [resolvedLibraryPath] supplies its demo library path when present.
 */
private fun Application.installDependencies(
    seedProfile: String?,
    applicationScope: CoroutineScope,
    resolvedLibraryPath: Path?,
    homeDir: Path,
    metadataPrecedence: MetadataPrecedence,
    embeddedCoverCacheSize: Int,
    watchEnabled: Boolean,
) {
    install(Koin) {
        val modules = mutableListOf(authModule(environment.config))
        modules += scannerModule(applicationScope, metadataPrecedence, watchEnabled)
        modules += booksModule(metadataPrecedence, embeddedCoverCacheSize, homeDir)
        modules += metadataModule(kotlinx.io.files.Path(homeDir.toString()))
        modules += playbackModule()
        modules += libraryModule()
        modules += embeddedmetaModule
        modules += syncModule()
        modules += publicProfileModule()
        modules += shelfModule()
        val httpPort =
            environment.config
                .propertyOrNull("ktor.deployment.port")
                ?.getString()
                ?.toIntOrNull() ?: 8080
        modules += mdnsModule(applicationScope, httpPort)
        modules += profileModule(homeDir.resolve("avatars"))
        modules += backupModule(homeDir)
        modules += importModule(homeDir)
        if (seedProfile == SEED_PROFILE_DEMO) {
            modules +=
                seedModule(
                    hasPlaybackModule = true,
                    hasBooksModule = true,
                    demoLibraryPath = resolvedLibraryPath?.toString(),
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
 * rate limiting, and the [com.calypsan.listenup.api.error.AppError] status-page mapper. Sequenced
 * after [install] of Koin in [module] because each reads a Koin-provided collaborator.
 */
private fun Application.installRequestPipeline() {
    installCallIdAndLogging()
    installRateLimiting()
    installAppErrorStatusPages()
}

fun Application.module() {
    installCorePlugins()

    val seedProfile = resolveSeedProfile()
    val applicationScope = CoroutineScope(coroutineContext + SupervisorJob())
    val resolvedLibraryPath = resolveLibraryPath() ?: resolveDemoLibraryFallback(seedProfile)
    val homeDir = resolveImageHome()
    val metadataPrecedence = resolveMetadataPrecedence()
    val embeddedCoverCacheSize = resolveEmbeddedCoverCacheSize()

    installDependencies(
        seedProfile,
        applicationScope,
        resolvedLibraryPath,
        homeDir,
        metadataPrecedence,
        embeddedCoverCacheSize,
        environment.config.watchEnabled(),
    )

    backfillPublicProfiles()
    launchSeeders(applicationScope, seedProfile, resolvedLibraryPath != null)

    installRequestPipeline()
    installMaintenanceGate(koinGet<MaintenanceState>())

    val jwt by inject<JwtConfiguration>()
    val sessions by inject<SessionService>()
    installJwtAuth(jwt, sessions)

    installAppRoutes(homeDir)

    startBackgroundTasks(applicationScope, resolvedLibraryPath)
    installGracefulShutdown(applicationScope)
}

/**
 * Releases every background resource when the application stops — real shutdown and each
 * `testApplication` teardown. Order: unmount watchers (close native FS handles), cancel the
 * background-task [applicationScope] (cleanup loops, the BookPersister scan-result collector,
 * bootstrap), then close the Hikari pool. Each step is best-effort and CANCELS rather than
 * joins: joining the watcher's blocking read would time out Ktor's application disposal. Ktor
 * drains in-flight requests before firing [ApplicationStopped], so nothing is using the pool.
 */
private fun Application.installGracefulShutdown(applicationScope: CoroutineScope) {
    // Resolve eagerly while Koin is still open — by ApplicationStopped the Koin scope is
    // already closed, so a lazy `by inject` access inside the handler would throw
    // ClosedScopeException. Capture the live references in the closure instead.
    val watcherSupervisor = koinGet<WatcherSupervisorPort>()
    val databaseHandle = koinGet<DatabaseHandle>()
    monitor.subscribe(ApplicationStopped) {
        runCatching { runBlocking { watcherSupervisor.unmountAll() } }
            .onFailure { logger.warn(it) { "watcher unmount on shutdown failed" } }
        runCatching { applicationScope.cancel("application stopped") }
            .onFailure { logger.warn(it) { "background-scope cancel on shutdown failed" } }
        runCatching { databaseHandle.close() }
            .onFailure { logger.warn(it) { "db pool close on shutdown failed" } }
    }
}

/**
 * Mounts every HTTP + RPC route on the application. Re-injects its own service dependencies (the
 * same idiom as [installDependencies] / [backfillPublicProfiles]) so [module] stays a high-level
 * lifecycle script rather than a flat wall of `by inject` declarations.
 *
 * @param homeDir the resolved ListenUp home dir, passed through from [module] for the metadata-image
 *   route (it is resolved once at boot and not held in Koin).
 */
private fun Application.installAppRoutes(homeDir: Path) {
    val authService by inject<AuthServiceImpl>()
    val adminUserService by inject<AdminUserServiceImpl>()
    val adminSettingsService by inject<AdminSettingsServiceImpl>()
    val inviteService by inject<InviteServiceImpl>()
    val instanceService by inject<InstanceService>()
    val registrationBroadcaster by inject<RegistrationBroadcaster>()
    val scannerService by inject<ScannerService>()
    val eventBus by inject<SharedFlow<ScanEvent>>()
    val bookService by inject<BookService>()
    val contributorService by inject<ContributorService>()
    val seriesService by inject<SeriesService>()
    val coverResponder by inject<CoverResponder>()
    val bookAccessPolicy by inject<BookAccessPolicy>()
    val playbackService by inject<PlaybackService>()
    val playbackProgressService by inject<PlaybackProgressService>()
    val backfillService by inject<UserStatsBackfillService>()
    val searchReindexService by inject<SearchReindexService>()
    val audioFileLocator by inject<AudioFileLocator>()
    val audioUrlSigner by inject<AudioUrlSigner>()
    val contributorRepository by inject<ContributorRepository>()
    val seriesRepository by inject<SeriesRepository>()
    val metadataLookupService by inject<MetadataLookupService>()
    val searchService by inject<SearchService>()
    val libraryAdminService by inject<LibraryAdminService>()
    val tagService by inject<TagService>()
    val moodService by inject<MoodService>()
    val genreService by inject<GenreService>()
    val collectionService by inject<CollectionService>()
    val shelfService by inject<ShelfService>()
    val socialService by inject<SocialService>()
    val activityService by inject<ActivityService>()
    val profileService by inject<ProfileService>()
    val backupService by inject<BackupService>()
    val importService by inject<ImportService>()
    val backupPaths by inject<com.calypsan.listenup.server.backup.BackupPaths>()
    val backupArchive by inject<com.calypsan.listenup.server.backup.BackupArchive>()
    val importPaths by inject<com.calypsan.listenup.server.absimport.ImportPaths>()
    val avatarImageStore by inject<ImageStore>()
    val db by inject<Database>()
    val audioRoleLookup by inject<UserRoleLookup>()

    routing {
        healthRoutes()
        instanceRoutes(instanceService)
        sseRoutes()
        authRoutes(authService)
        publicInviteRoutes(inviteService)
        registrationStatusRoutes(registrationBroadcaster)
        rpcRoutes(
            authService,
            instanceService,
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
            moodService,
            genreService,
            collectionService,
            shelfService,
            socialService,
            activityService,
            adminUserService,
            adminSettingsService,
            inviteService,
            profileService,
            backupService,
            importService,
        )
        authenticate(JWT_PROVIDER) {
            syncRoutes()
            adminUserRoutes(adminUserService)
            adminInviteRoutes(inviteService)
            libraryAdminRoutes(libraryAdminService)
            bookRoutes(bookService, coverResponder, bookAccessPolicy)
            contributorRoutes(contributorService, bookAccessPolicy)
            seriesRoutes(seriesService, bookAccessPolicy)
            playbackRoutes(playbackService)
            playbackProgressRoutes(playbackProgressService, bookAccessPolicy)
            adminRoutes(backfillService, searchReindexService)
            metadataImageRoutes(contributorRepository, seriesRepository, homeDir)
            metadataRoutes(metadataLookupService)
            searchRoutes(searchService)
            tagRoutes(tagService, bookAccessPolicy)
            genreRoutes(genreService, bookAccessPolicy)
            collectionRoutes(collectionService)
            collectionAdminRoutes(collectionService)
            profileRoutes(db, avatarImageStore)
            backupRoutes(backupPaths, backupArchive)
            importRoutes(importPaths)
        }
        scannerRoutes(scannerService, eventBus)
        audioRoutes(audioFileLocator, audioUrlSigner, audioRoleLookup, bookAccessPolicy)
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
 * Resolves the always-available ListenUp home directory that holds app-managed
 * files (downloaded cover/contributor images live in per-type subdirectories
 * under it). Unlike the audio library, this path is always available — even on a
 * library-less boot — so it is resolved unconditionally.
 *
 * An explicit `listenup.home` config property wins (tests inject this to a temp
 * dir); otherwise it falls back to `$LISTENUP_HOME`, defaulting to `~/ListenUp`.
 * Reads env / system properties here at the config edge so [resolveListenupHome]
 * stays pure.
 */
private fun Application.resolveImageHome(): Path {
    val configured =
        environment.config
            .propertyOrNull("listenup.home")
            ?.getString()
            ?.takeIf { it.isNotBlank() }
    if (configured != null) return Path.of(configured)
    return resolveListenupHome(System.getenv("LISTENUP_HOME"), System.getProperty("user.home"))
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
 * Starts all background scheduler tasks. Every task — session cleanup, the
 * scanner-dependent cleanup tasks, and library bootstrap — runs unconditionally:
 * the library-dependent Koin modules now load regardless of whether a library
 * path is configured, and [bootstrapLibraries] handles the zero-library case by
 * idling until a client onboards a library at runtime.
 *
 * [libraryPath] (the env-var / config path, nullable) is forwarded to
 * [bootstrapLibraries] so a configured path still creates the default library
 * while a library-less boot starts cleanly and awaits client onboarding.
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

    val orchestrator by inject<ScanOrchestrator>()
    val bookPersister by inject<BookPersister>()
    val libraryAdminService by inject<LibraryAdminService>()

    // BookPersister must be subscribed to the scan-result bus before any scan can run
    // — a scan can now be triggered at runtime via the wizard on a library-less boot.
    bookPersister.start()

    val cleanupTask by inject<ActiveSessionCleanupTask>()
    cleanupTask.start(scope)
    val metadataCacheCleanupTask by inject<MetadataCacheCleanupTask>()
    metadataCacheCleanupTask.start(scope)
    val orphanImageCleanupTask by inject<OrphanImageCleanupTask>()
    orphanImageCleanupTask.start(scope)

    val rescanOnStartup = environment.config.rescanOnStartup()
    scope.launch {
        runCatching {
            bootstrapLibraries(
                libraryAdminService = libraryAdminService,
                scanOrchestrator = orchestrator,
                libraryPath = libraryPath?.toString(),
                rescanOnStartup = rescanOnStartup,
            )
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.error(e) { "library bootstrap failed — server keeps running" }
        }
    }

    RescanScheduler(
        scope = scope,
        interval = environment.config.periodicRescanInterval(),
        libraryIds = { orchestrator.registeredLibraryIds() },
        rescan = { orchestrator.scanLibraryAsync(it) },
    ).start()

    // mDNS advertisement — best-effort, non-fatal. Resolve the persistent instance id, then start the
    // advertiser; register its stop on shutdown. A failure here must never break startup — manual
    // server-URL entry is the Never-Stranded fallback. Gated by `mdns.enabled` (default true) so the
    // test harness can opt out — no test should bind multicast sockets or run a receive loop.
    if (environment.config
            .propertyOrNull("mdns.enabled")
            ?.getString()
            ?.toBooleanStrictOrNull() != false
    ) {
        scope.launch {
            runCatching {
                val advertiser = koinGet<MdnsAdvertiser>()
                advertiser.start()
                // Re-announce when an admin changes the server name / remote URL: that path broadcasts
                // SyncControl.ServerInfoChanged, which this collector turns into an mDNS refresh so the
                // new identity reaches LAN clients without a restart.
                scope.launchMdnsRefreshOnServerInfoChange(koinGet<ChangeBus>(), advertiser)
                monitor.subscribe(ApplicationStopped) {
                    scope.launch { advertiser.stop() }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.warn(e) { "mDNS advertisement failed to start — server keeps running" }
            }
        }
    }
}

private fun ApplicationConfig.rescanOnStartup(): Boolean =
    propertyOrNull("scan.rescanOnStartup")?.getString()?.toBoolean() ?: true

/**
 * Reads `scanner.watchEnabled` — gates whether [ScanOrchestrator.onLibraryAdded]
 * mounts real-time file-system watchers. Defaults to `true` (production keeps the
 * live `WatchService`). Tests set it `false` so a fixture write into the library
 * root can't trigger a scan that races the seed (mirrors the `mdns.enabled` gate).
 */
private fun ApplicationConfig.watchEnabled(): Boolean =
    propertyOrNull("scanner.watchEnabled")?.getString()?.toBoolean() ?: true

private fun ApplicationConfig.periodicRescanInterval(): Duration =
    propertyOrNull("scan.periodicRescanInterval")
        ?.getString()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Duration.parse(it) }.getOrNull() }
        ?: Duration.ZERO

// The synthetic ROOT principal used by [bootstrapLibraries]. Startup library bootstrap
// has no signed-in caller — it is the system acting as administrator — so it binds the
// admin-gated LibraryAdminService to this principal to pass the structural-op gate.
private val SYSTEM_BOOTSTRAP_PRINCIPAL =
    PrincipalProvider {
        UserPrincipal(UserId("system-bootstrap"), SessionId("system-bootstrap"), UserRole.ROOT)
    }

/**
 * One-shot library bootstrap called at server startup and in [ApplicationBootstrapTest].
 *
 * Rules (Task 18):
 *  - If libraries already exist → register each with [scanOrchestrator] via
 *    [ScanOrchestrator.onLibraryAdded] so the watcher and scanner bundle are
 *    warmed up. When [rescanOnStartup] is true each library is also kicked off
 *    via [ScanOrchestrator.scanLibraryAsync] to catch changes made while the
 *    server was down.
 *  - If no libraries exist and [libraryPath] is non-null → create a single
 *    default "My Library" pointing at that path. [LibraryAdminServiceImpl.createLibrary]
 *    internally calls [ScanOrchestrator.onLibraryAdded], so we don't need to.
 *    The `runCatching` guard handles the narrow race where a concurrent caller
 *    (e.g. a test fixture seeding the DB) inserts the same root_path between
 *    our `listLibraries` check and the `createLibrary` insert. On any error,
 *    we re-read the table and call `onLibraryAdded` for whatever ended up there.
 *    When [rescanOnStartup] is true the library is scanned immediately after.
 *  - If no libraries exist and [libraryPath] is null → log and return; the
 *    operator must create a library via the LibraryAdmin REST surface.
 *
 * Startup scans are gated by [rescanOnStartup] so tests can opt out and avoid
 * racing scanner coroutines against test fixture assertions.
 */
internal suspend fun bootstrapLibraries(
    libraryAdminService: LibraryAdminService,
    scanOrchestrator: ScanOrchestrator,
    libraryPath: String?,
    rescanOnStartup: Boolean = true,
) {
    // Startup bootstrap is a system operation — it creates the default library from the
    // env var before any user has signed in. Scope the admin-gated service to a synthetic
    // ROOT caller so the structural-op gate sees the system, not an absent principal.
    val service = (libraryAdminService as LibraryAdminServiceImpl).copyWith(SYSTEM_BOOTSTRAP_PRINCIPAL)
    val existingResult = service.listLibraries()
    if (existingResult is AppResult.Failure) {
        logger.warn { "bootstrap: could not list libraries — ${existingResult.error.message}" }
        return
    }
    val existing = (existingResult as AppResult.Success).data

    when {
        existing.isNotEmpty() -> {
            logger.info { "bootstrap: ${existing.size} library(s) already configured; env var ignored" }
            val library = existing.firstOrNull()
            if (library != null) {
                scanOrchestrator.onLibraryAdded(library)
                if (rescanOnStartup) scanOrchestrator.scanLibraryAsync(library.id)
            }
        }

        libraryPath != null -> {
            bootstrapCreateDefaultLibrary(service, scanOrchestrator, libraryPath, rescanOnStartup)
        }

        else -> {
            logger.info {
                "bootstrap: no libraries and no env var; awaiting client onboarding via LibraryAdminService.createLibrary"
            }
        }
    }
}

/**
 * Re-reads the library table after a failed [bootstrapCreateDefaultLibrary] attempt
 * and registers whatever ended up there (handles the concurrent-insert race). When
 * [rescanOnStartup] is true each library is also kicked off via
 * [ScanOrchestrator.scanLibraryAsync].
 */
private suspend fun bootstrapRecheckAndRegister(
    libraryAdminService: LibraryAdminService,
    scanOrchestrator: ScanOrchestrator,
    rescanOnStartup: Boolean,
) {
    val recheck = libraryAdminService.listLibraries()
    if (recheck is AppResult.Success && recheck.data.isNotEmpty()) {
        logger.info {
            "bootstrap: found ${recheck.data.size} library(s) after re-check; registering with orchestrator"
        }
        val library = recheck.data.firstOrNull()
        if (library != null) {
            scanOrchestrator.onLibraryAdded(library)
            if (rescanOnStartup) scanOrchestrator.scanLibraryAsync(library.id)
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
    rescanOnStartup: Boolean = true,
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
        if (rescanOnStartup) scanOrchestrator.scanLibraryAsync(created.data.id)
    } else {
        // Either createLibrary returned Failure or threw — re-check the DB.
        // A concurrent insert (test fixture race) may have already added a library.
        if (created is AppResult.Failure) {
            logger.warn { "bootstrap: createLibrary returned ${created.error.code} — re-checking" }
        } else {
            logger.warn { "bootstrap: createLibrary threw — re-checking for concurrent inserts" }
        }
        bootstrapRecheckAndRegister(libraryAdminService, scanOrchestrator, rescanOnStartup)
    }
}
