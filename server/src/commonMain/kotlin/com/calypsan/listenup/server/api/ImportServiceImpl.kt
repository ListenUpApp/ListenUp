package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.absimport.ImportAnalyzer
import com.calypsan.listenup.server.absimport.ImportApplier
import com.calypsan.listenup.server.absimport.ImportStore
import com.calypsan.listenup.server.absimport.MappingValidator
import com.calypsan.listenup.server.absimport.isSafeImportId
import com.calypsan.listenup.server.auth.PrincipalProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Admin-only Audiobookshelf-import surface. Every method is gated behind [requireAdmin]; the binary
 * upload lives on a separate REST route ([com.calypsan.listenup.server.routes.importRoutes]).
 *
 * The service is a thin orchestrator over the staging primitives: [ImportAnalyzer] reads the staged
 * ABS database and produces the match preview, [MappingValidator] rejects an incoherent admin
 * mapping before it is written, [ImportStore] persists the confirmed mapping and exposes filesystem-
 * truth job state, and [ImportApplier] writes the listening progress through the playback repository.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries an unscoped placeholder that throws if invoked without binding.
 */
class ImportServiceImpl(
    private val store: ImportStore,
    private val analyzer: ImportAnalyzer,
    private val applier: ImportApplier,
    private val validator: MappingValidator,
    private val eventBus: MutableSharedFlow<ImportEvent>,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : ImportService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): ImportServiceImpl =
        ImportServiceImpl(store, analyzer, applier, validator, eventBus, provider)

    override suspend fun analyze(importId: ImportId): AppResult<ImportAnalysis> {
        requireAdmin()?.let { return it }
        rejectUnsafeId(importId)?.let { return it }
        return analyzer.analyze(importId) { eventBus.tryEmit(it) }
    }

    override suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit> {
        requireAdmin()?.let { return it }
        rejectUnsafeId(importId)?.let { return it }
        if (store.getImport(importId) == null) {
            return AppResult.Failure(ImportError.ImportNotFound())
        }
        validator.validateMapping(userMappings, bookOverrides)?.let { return AppResult.Failure(it) }
        store.writeMapping(importId, userMappings, bookOverrides)
        return AppResult.Success(Unit)
    }

    override suspend fun apply(importId: ImportId): AppResult<ImportResult> {
        requireAdmin()?.let { return it }
        rejectUnsafeId(importId)?.let { return it }
        return applier.apply(importId) { eventBus.tryEmit(it) }
    }

    override suspend fun listImports(): AppResult<List<ImportSummary>> {
        requireAdmin()?.let { return it }
        return AppResult.Success(store.listImports())
    }

    override suspend fun getImport(importId: ImportId): AppResult<ImportSummary> {
        requireAdmin()?.let { return it }
        rejectUnsafeId(importId)?.let { return it }
        return store.getImport(importId)?.let { AppResult.Success(it) }
            ?: AppResult.Failure(ImportError.ImportNotFound())
    }

    override suspend fun deleteImport(importId: ImportId): AppResult<Unit> {
        requireAdmin()?.let { return it }
        rejectUnsafeId(importId)?.let { return it }
        return if (store.deleteImport(importId)) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(ImportError.ImportNotFound())
        }
    }

    /**
     * Streams import-progress events to admins; non-admins get an empty stream (no information leak).
     *
     * The [eventBus] is a single process-wide stream shared by analyze and apply, so [importId] is
     * not used to filter — the events carry no import id, and at most one import runs interactively
     * at a time. A first-class per-import filter would require widening the contract; a shared stream
     * is sufficient for v1.
     */
    override fun observeProgress(importId: ImportId): Flow<RpcEvent<ImportEvent>> =
        if (principal.current()?.role?.isAdmin() == true) {
            eventBus.map { RpcEvent.Data(it) }
        } else {
            emptyFlow()
        }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * null = safe id; an [ImportError.ImportNotFound] Failure for a client-supplied id that could
     * escape the imports directory (path separators / `..`). Reported as not-found so a traversal
     * probe learns nothing. Mirrors the backup surface's `isSafeBackupId` guard.
     */
    private fun rejectUnsafeId(importId: ImportId): AppResult.Failure? =
        if (isSafeImportId(importId.value)) null else AppResult.Failure(ImportError.ImportNotFound())

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN
}
