package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizePreviewEntryDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.error.surfacedLogLine
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.isAdmin
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.LibraryWriteStatus
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.organize.MovePlan
import com.calypsan.listenup.server.organize.MoveManifestExecutor
import com.calypsan.listenup.server.organize.OrganizePlanBuilder
import com.calypsan.listenup.server.organize.OrganizeRunState
import com.calypsan.listenup.server.organize.OrganizerSettingsStore
import com.calypsan.listenup.server.organize.toPlannerSettings
import com.calypsan.listenup.server.services.LibraryRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.time.TimeSource

private val logger = loggerFor<OrganizeServiceImpl>()

/** How many before→after rows a preview response carries; the summary counts always cover the full plan. */
private const val PREVIEW_ENTRY_LIMIT = 50

/**
 * [OrganizeService] implementation — the admin-only save-moment orchestration (#850). Planning
 * is delegated to [OrganizePlanBuilder] (pure read), execution to [MoveManifestExecutor]
 * (journaled broker moves + DB path updates), progress to [OrganizeRunState]. Admin-gated via
 * [requireAdmin]; route handlers bind the caller via [copyWith] (the Koin singleton carries an
 * unscoped placeholder).
 *
 * Never Stranded: a sweep probes every library folder root for writability first — an unwritable
 * root fails [saveAndExecute] typed and persists nothing, while [saveSettings] still succeeds
 * (recording rules touches no files). Mid-run failures skip the failed book and keep going; the
 * terminal [OrganizeRunEvent.Completed] carries the failure count, and a fresh [saveAndExecute] is
 * the resume (it re-plans the remainder).
 */
class OrganizeServiceImpl(
    private val settingsStore: OrganizerSettingsStore,
    private val planBuilder: OrganizePlanBuilder,
    private val executor: MoveManifestExecutor,
    private val broker: LibraryWriteBroker,
    private val libraryRegistry: LibraryRegistry,
    private val sql: ListenUpDatabase,
    private val runState: OrganizeRunState,
    private val runScope: CoroutineScope,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : OrganizeService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): OrganizeServiceImpl =
        OrganizeServiceImpl(
            settingsStore = settingsStore,
            planBuilder = planBuilder,
            executor = executor,
            broker = broker,
            libraryRegistry = libraryRegistry,
            sql = sql,
            runState = runState,
            runScope = runScope,
            principal = provider,
        )

    override suspend fun getSettings(): AppResult<OrganizeSettingsDto> {
        requireAdmin()?.let { return it }
        return AppResult.Success(settingsStore.get())
    }

    override suspend fun saveSettings(settings: OrganizeSettingsDto): AppResult<Unit> {
        requireAdmin()?.let { return it }
        // No root probe: persisting rules writes to `server_settings`, never to a library folder.
        // An unavailable disk must not stop an admin from saying how they want things filed.
        settingsStore.set(settings)
        return AppResult.Success(Unit)
    }

    override suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto> {
        requireAdmin()?.let { return it }
        val plan = planBuilder.build(libraryRegistry.currentLibrary(), settings.toPlannerSettings())
        return AppResult.Success(plan.toPreviewDto())
    }

    override suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId> {
        requireAdmin()?.let { return it }

        probeAllRoots()?.let { unavailable -> return AppResult.Failure(unavailable) }

        val plan = planBuilder.build(libraryRegistry.currentLibrary(), settings.toPlannerSettings())
        val runId =
            runState.begin()
                ?: return AppResult.Failure(
                    LibraryWriteError.Unavailable(debugInfo = "an organize run is already in flight"),
                )
        settingsStore.set(settings)

        runScope.launch {
            executeRun(runId, plan)
        }
        return AppResult.Success(runId)
    }

    override fun observeRun(runId: OrganizeRunId): Flow<RpcEvent<OrganizeRunEvent>> =
        if (principal.current()?.run { role.isAdmin() } == true) {
            flow {
                runState.eventsFor(runId).collect { event -> emit(RpcEvent.Data(event)) }
            }
        } else {
            // Non-admins get an empty stream rather than a typed error — matching the
            // BackupServiceImpl/ImportServiceImpl observe-method idiom for streaming RPCs.
            emptyFlow()
        }

    override suspend fun resumeRun(): AppResult<OrganizeRunId?> {
        requireAdmin()?.let { return it }
        return AppResult.Success(runState.activeRunId())
    }

    /**
     * Runs [plan] to completion, emitting progress into [runState]. Never throws — per-book failures are reported and
     * skipped.
     *
     * Logs the pass for the operator reading the server log: its plan and its result at INFO, every failed book at
     * WARN with the paths involved and the error's code and detail. A successful move is DEBUG only — a first pass over
     * a real library moves thousands of books, and those lines would bury the failures.
     */
    private suspend fun executeRun(
        runId: OrganizeRunId,
        plan: MovePlan,
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val total = plan.entries.size
        logger.info {
            "organize pass ${runId.value} started: $total books planned " +
                "(${plan.bookCount} relocations of ${plan.fileCount} files, ${plan.renamedInPlaceCount} in-place renames)"
        }
        runState.emit(runId, OrganizeRunEvent.Started(runId, total))
        var moved = 0
        var failed = 0
        for (entry in plan.entries) {
            when (val result = executor.execute(entry)) {
                is AppResult.Success -> {
                    moved++
                    logger.debug { "organize moved book ${entry.bookId}: ${entry.fromDir} → ${entry.toDir}" }
                    runState.emit(
                        runId,
                        OrganizeRunEvent.BookMoved(
                            bookId = entry.bookId,
                            toPath = entry.toRootRelPath,
                            completed = moved + failed,
                            totalBooks = total,
                        ),
                    )
                }

                is AppResult.Failure -> {
                    failed++
                    logger.warn {
                        "organize move failed for book ${entry.bookId}: ${entry.fromDir} → ${entry.toDir}: " +
                            result.error.surfacedLogLine()
                    }
                    runState.emit(
                        runId,
                        OrganizeRunEvent.BookFailed(
                            bookId = entry.bookId,
                            reason = result.error.message,
                            completed = moved + failed,
                            totalBooks = total,
                        ),
                    )
                }
            }
        }
        logger.info {
            "organize pass ${runId.value} finished in ${startedAt.elapsedNow().inWholeMilliseconds} ms: " +
                "$moved moved, $failed failed of $total planned"
        }
        runState.emit(runId, OrganizeRunEvent.Completed(movedBooks = moved, failedBooks = failed))
    }

    /** Probes every live library folder root; the first unwritable one's typed error, or null when all are writable. */
    private suspend fun probeAllRoots(): LibraryWriteError? {
        val libraryId = libraryRegistry.currentLibrary()
        val roots =
            suspendTransaction(sql) {
                sql.libraryFoldersQueries
                    .listByLibrary(libraryId.value)
                    .executeAsList()
                    .map { it.root_path }
            }
        for (root in roots) {
            val status = broker.probe(Path(root))
            if (status is LibraryWriteStatus.Unavailable) {
                return LibraryWriteError.Unavailable(debugInfo = status.reason)
            }
        }
        return null
    }

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return PermissionPolicy.requireAdmin(caller)?.let { AppResult.Failure(it) }
    }

    private fun MovePlan.toPreviewDto(): OrganizePreviewDto =
        OrganizePreviewDto(
            bookCount = bookCount,
            fileCount = fileCount,
            collisionCount = collisionCount,
            renamedInPlaceCount = renamedInPlaceCount,
            entries =
                entries.take(PREVIEW_ENTRY_LIMIT).map { entry ->
                    OrganizePreviewEntryDto(
                        bookId = entry.bookId,
                        fromPath = entry.fromDir.toString(),
                        toPath = entry.toRootRelPath,
                        collisionResolved = entry.collisionResolved,
                        // Only an in-place rename carries the filenames: on a relocation the folder
                        // row is the headline, and a second pair would just be noise.
                        renamedFrom = entry.audioRename?.run { from.takeUnless { entry.isRelocation } },
                        renamedTo = entry.audioRename?.run { to.takeUnless { entry.isRelocation } },
                    )
                },
            truncated = entries.size > PREVIEW_ENTRY_LIMIT,
        )
}
