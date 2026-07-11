package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizePreviewEntryDto
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.auth.PrincipalProvider
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
 * Never Stranded: before persisting `enabled=true`, every library folder root is probed for
 * writability — an unwritable root fails typed and the toggle stays off. Mid-run failures skip
 * the failed book and keep going; the terminal [OrganizeRunEvent.Completed] carries the failure
 * count, and a fresh [saveAndExecute] is the resume (it re-plans the remainder).
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
            settingsStore,
            planBuilder,
            executor,
            broker,
            libraryRegistry,
            sql,
            runState,
            runScope,
            provider,
        )

    override suspend fun getSettings(): AppResult<OrganizeSettingsDto> {
        requireAdmin()?.let { return it }
        return AppResult.Success(settingsStore.get())
    }

    override suspend fun preview(settings: OrganizeSettingsDto): AppResult<OrganizePreviewDto> {
        requireAdmin()?.let { return it }
        val plan = planBuilder.build(libraryRegistry.currentLibrary(), settings.toPlannerSettings())
        return AppResult.Success(plan.toPreviewDto())
    }

    override suspend fun saveAndExecute(settings: OrganizeSettingsDto): AppResult<OrganizeRunId> {
        requireAdmin()?.let { return it }

        if (!settings.enabled) {
            // Disable = stop: persist the schema (always possible, even against an unwritable
            // root) and start nothing. Nothing is ever un-organized.
            settingsStore.set(settings)
            return AppResult.Success(OrganizeRunId(NO_RUN))
        }

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
        if (principal.current()?.role?.isAdmin() == true) {
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

    /** Runs [plan] to completion, emitting progress into [runState]. Never throws — per-book failures are reported and skipped. */
    private suspend fun executeRun(
        runId: OrganizeRunId,
        plan: MovePlan,
    ) {
        val total = plan.entries.size
        runState.emit(runId, OrganizeRunEvent.Started(runId, total))
        var moved = 0
        var failed = 0
        for (entry in plan.entries) {
            when (val result = executor.execute(entry)) {
                is AppResult.Success -> {
                    moved++
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
                    logger.warn { "organize move failed for ${entry.bookId}: ${result.error.debugInfo}" }
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
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

    private fun MovePlan.toPreviewDto(): OrganizePreviewDto =
        OrganizePreviewDto(
            bookCount = bookCount,
            fileCount = fileCount,
            collisionCount = collisionCount,
            entries =
                entries.take(PREVIEW_ENTRY_LIMIT).map { entry ->
                    OrganizePreviewEntryDto(
                        bookId = entry.bookId,
                        fromPath = entry.fromDir.toString(),
                        toPath = entry.toRootRelPath,
                        collisionResolved = entry.collisionResolved,
                    )
                },
            truncated = entries.size > PREVIEW_ENTRY_LIMIT,
        )

    private companion object {
        /** Sentinel run id returned by a disabled save — no run was started, nothing to observe. */
        const val NO_RUN = "no-run"
    }
}
