@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the admin Audiobookshelf import domain.
 *
 * Suspend methods return [AppResult] so callers exhaustively fold over typed
 * [com.calypsan.listenup.api.error.ImportError] values instead of catching
 * exceptions. The binary `.audiobookshelf` upload that creates an import lives on a
 * REST route; this repository covers the post-upload lifecycle — analyze, confirm the
 * suggested mapping, apply, and inspect/delete staged jobs.
 *
 * [observeProgress] is a cold server-pushed stream of progress events for both analyze
 * and apply; call sites convert it to hot state with `.stateIn(scope)` as needed.
 *
 * Implementations back this contract with the [com.calypsan.listenup.api.ImportService]
 * RPC channel ([com.calypsan.listenup.client.data.remote.RpcChannel]).
 */
interface ImportRepository {
    /**
     * Streams a `.audiobookshelf` backup zip to the server, staging a new import job.
     *
     * This is the one REST operation in the import domain — binary multipart transfer
     * cannot ride RPC. Returns the created [ImportSummary] on success; the [ImportSummary.id]
     * is then used to drive the post-upload lifecycle via the remaining RPC methods.
     */
    suspend fun upload(fileSource: FileSource): AppResult<ImportSummary>

    /**
     * Reads the staged ABS backup for [importId] and produces a confidence-tiered
     * [ImportAnalysis] previewing the ABS-user → ListenUp-user and ABS-item → book matches.
     */
    suspend fun analyze(importId: ImportId): AppResult<ImportAnalysis>

    /**
     * Persists the admin-confirmed mappings for [importId].
     *
     * [userMappings] maps each ABS user to a ListenUp user. [bookOverrides] supplies manual
     * book assignments for ambiguous or unmatched items; a null value means "skip this item".
     */
    suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit>

    /**
     * Applies the confirmed mappings for [importId], writing ABS listening progress into the
     * ListenUp database. Idempotent via last-played-wins semantics.
     */
    suspend fun apply(importId: ImportId): AppResult<ImportResult>

    /** Returns summaries for all staged ABS import jobs, most recent first. */
    suspend fun listImports(): AppResult<List<ImportSummary>>

    /** Deletes the staging directory for the import identified by [importId]. */
    suspend fun deleteImport(importId: ImportId): AppResult<Unit>

    /**
     * Cold server-pushed stream of [ImportEvent] progress markers emitted during analyze and
     * apply for [importId]. Subscribe before triggering the operation to avoid missing early
     * events. Convert to hot state at the call site with `.stateIn(scope)`.
     */
    fun observeProgress(importId: ImportId): Flow<ImportEvent>
}
