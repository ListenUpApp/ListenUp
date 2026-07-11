package com.calypsan.listenup.server

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.LibraryWriteStatus
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.mdns.launchMdnsRefreshOnServerInfoChange
import com.calypsan.listenup.server.scanner.RescanScheduler
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask
import com.calypsan.listenup.server.scheduler.CampfireReaperTask
import com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.scheduler.StatsFreshnessSweepTask
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.ChangeBus
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.ext.inject

/**
 * Starts all background scheduler tasks. Every task — session cleanup, the
 * scanner-dependent cleanup tasks, and library bootstrap — runs unconditionally:
 * the library-dependent Koin modules now load regardless of whether library
 * paths are configured, and [bootstrapLibraries] handles the zero-paths case by
 * ensuring the singleton library exists and idling until a client adds folders.
 *
 * [libraryPaths] (the env-var / config paths, possibly empty) is forwarded to
 * [bootstrapLibraries] so configured paths are seeded as folders of the singleton
 * while a path-less boot starts cleanly and awaits client onboarding.
 *
 * [bootstrapLibraries] is launched in the background — callers should not
 * assume it has completed when this function returns.
 */
internal fun Application.startBackgroundTasks(
    scope: CoroutineScope,
    libraryPaths: List<Path>,
) {
    // Session cleanup runs unconditionally — sessions exist regardless of library config.
    inject<ExpiredSessionCleanupTask>().value.start(scope)

    val orchestrator by inject<ScanOrchestrator>()
    val bookPersister by inject<BookPersister>()
    val libraryAdminService by inject<LibraryAdminService>()
    val libraryRegistry by inject<LibraryRegistry>()

    // Sweep any spool dirs left by a crashed scan before the persister starts — the
    // spool is only read during persist, so clearing orphans here is always safe.
    koinGet<com.calypsan.listenup.server.scanner.CoverSpool>().sweepOrphans()

    // BookPersister must be subscribed to the scan-result bus before any scan can run
    // — a scan can now be triggered at runtime via the wizard on a library-less boot.
    bookPersister.start()

    val cleanupTask by inject<ActiveSessionCleanupTask>()
    cleanupTask.start(scope)
    val metadataCacheCleanupTask by inject<MetadataCacheCleanupTask>()
    metadataCacheCleanupTask.start(scope)
    val orphanImageCleanupTask by inject<OrphanImageCleanupTask>()
    orphanImageCleanupTask.start(scope)
    val statsFreshnessSweepTask by inject<StatsFreshnessSweepTask>()
    statsFreshnessSweepTask.start(scope)
    val campfireReaperTask by inject<CampfireReaperTask>()
    campfireReaperTask.start(scope)

    // Heal any ABS import whose apply was interrupted by a crash: re-running is idempotent and
    // restores stats + the client nudge. A path-less boot is a no-op (the imports dir is absent).
    // Never fatal to startup.
    scope.launchNeverFatal("interrupted-import resume failed") {
        koinGet<com.calypsan.listenup.server.absimport.InterruptedImportResumer>().resumeAll()
    }

    val rescanOnStartup = environment.config.rescanOnStartup()
    val libraryWriteBroker = koinGet<LibraryWriteBroker>()
    val libraryFolderRepository = koinGet<LibraryFolderRepository>()
    scope.launch {
        // Write-journal recovery MUST complete before bootstrapLibraries mounts any watcher
        // (onLibraryAdded): recovery re-registers every path it touches with the
        // SelfWriteRegistry, so ordering it first guarantees no watcher can observe a
        // recovery write as an external change and churn a rescan. Sequential in this
        // coroutine — the ordering is structural, not timing-based.
        runNeverFatal("write-journal recovery failed") {
            libraryWriteBroker.recoverJournal()
        }
        runNeverFatal("library bootstrap failed") {
            bootstrapLibraries(
                libraryAdminService = libraryAdminService,
                scanOrchestrator = orchestrator,
                libraryRegistry = libraryRegistry,
                libraryPaths = libraryPaths,
                rescanOnStartup = rescanOnStartup,
            )
        }
        // Writability probe — after folders load, so every live folder root gets a status
        // line in the boot log. Purely informational at this phase (the admin surface that
        // exposes LibraryWriteStatus to clients ships in a later phase).
        runNeverFatal("library writability probe failed") {
            probeLibraryFolders(libraryWriteBroker, libraryRegistry, libraryFolderRepository)
        }
    }

    RescanScheduler(
        scope = scope,
        interval = environment.config.periodicRescanInterval(),
        libraryId = { orchestrator.registeredLibraryId() },
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

/**
 * Runs [task] as a startup step that must never break server boot: any non-cancellation
 * failure is logged (prefixed with [description]) and swallowed.
 * [kotlinx.coroutines.CancellationException] is re-raised to honor structured concurrency.
 */
private suspend fun runNeverFatal(
    description: String,
    task: suspend () -> Unit,
) {
    runCatching { task() }.onFailure { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        logger.error(e) { "$description — server keeps running" }
    }
}

/** Fire-and-forget variant of [runNeverFatal] for independent startup jobs. */
private fun CoroutineScope.launchNeverFatal(
    description: String,
    task: suspend () -> Unit,
) = launch { runNeverFatal(description, task) }

/**
 * Probes every live library folder root for writability at boot and logs the outcome —
 * `info` when writable, `warn` with the reason when not. Purely informational: an unwritable
 * root degrades library writes to typed [com.calypsan.listenup.api.error.LibraryWriteError]
 * failures at call time; it never blocks startup.
 */
internal suspend fun probeLibraryFolders(
    broker: LibraryWriteBroker,
    libraryRegistry: LibraryRegistry,
    folderRepository: LibraryFolderRepository,
) {
    val libraryId = libraryRegistry.currentLibrary()
    for (folder in folderRepository.listByLibrary(libraryId.value)) {
        when (val status = broker.probe(Path(folder.rootPath))) {
            is LibraryWriteStatus.Available -> {
                logger.info { "library folder ${folder.rootPath} is writable" }
            }

            is LibraryWriteStatus.Unavailable -> {
                logger.warn { "library folder ${folder.rootPath} is NOT writable — ${status.reason}" }
            }
        }
    }
}

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
 * Singleton model:
 *  1. Ensures the singleton library exists by calling [LibraryRegistry.currentLibrary]
 *     (creates a path-less row named "Library" if the DB is empty — idempotent across boots).
 *  2. Seeds [libraryPaths] as folders of the singleton, but ONLY when the library currently
 *     has no folders. This guards against re-adding on subsequent boots while still honoring
 *     the env var on a fresh install. Folders that [addFolder] rejects (invalid path,
 *     duplicate) are logged and skipped — they never crash the bootstrap.
 *  3. Registers the library with [scanOrchestrator] via [ScanOrchestrator.onLibraryAdded] so
 *     the watcher and scanner bundle are warmed up, then kicks off a scan when [rescanOnStartup]
 *     is true and the library has at least one folder.
 *
 * Startup scans are gated by [rescanOnStartup] so tests can opt out and avoid
 * racing scanner coroutines against test fixture assertions.
 */
internal suspend fun bootstrapLibraries(
    libraryAdminService: LibraryAdminService,
    scanOrchestrator: ScanOrchestrator,
    libraryRegistry: LibraryRegistry,
    libraryPaths: List<Path>,
    rescanOnStartup: Boolean = true,
) {
    // Startup bootstrap is a system operation — it runs before any user has signed in.
    // Scope the admin-gated service to a synthetic ROOT caller so the structural-op gate
    // sees the system, not an absent principal.
    val service = (libraryAdminService as LibraryAdminServiceImpl).copyWith(SYSTEM_BOOTSTRAP_PRINCIPAL)

    // The singleton always exists (LibraryRegistry creates it on first resolve).
    libraryRegistry.currentLibrary()

    // Seed env paths as folders only when the library has none yet (idempotent across boots).
    val current = service.getLibrary()
    val hasFolders = current is AppResult.Success && current.data.folders.isNotEmpty()
    if (!hasFolders) {
        for (path in libraryPaths) {
            when (val added = service.addFolder(path.toString())) {
                is AppResult.Failure -> logger.warn { "bootstrap: skipped folder $path — ${added.error.code}" }
                is AppResult.Success -> logger.info { "bootstrap: seeded folder $path" }
            }
        }
    }

    when (val lib = service.getLibrary()) {
        is AppResult.Success -> {
            scanOrchestrator.onLibraryAdded(lib.data)
            if (rescanOnStartup && lib.data.folders.isNotEmpty()) {
                scanOrchestrator.scanLibraryAsync(lib.data.id)
            }
        }

        is AppResult.Failure -> {
            logger.warn { "bootstrap: could not resolve the library — ${lib.error.message}" }
        }
    }
}
