package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/** Admin-only Audiobookshelf import surface. Binary upload lives on a REST route. */
@Rpc
interface ImportService {
    /**
     * Reads the staged ABS backup and produces a confidence-tiered [ImportAnalysis]
     * mapping ABS users → ListenUp users and ABS items → ListenUp books.
     *
     * Persists `analysis.json` in the import staging directory. Emits [ImportEvent]
     * progress on [observeProgress]. Idempotent — re-analyzing overwrites the previous result.
     */
    suspend fun analyze(importId: ImportId): AppResult<ImportAnalysis>

    /**
     * Persists the admin-confirmed user and book mappings to `mapping.json`.
     *
     * [userMappings] maps each ABS user ID to a ListenUp user ID.
     * [bookOverrides] provides manual book assignments for ambiguous or unmatched items;
     * a null value means "skip this item". Entries for confidently matched items are ignored.
     *
     * Returns [com.calypsan.listenup.api.error.ImportError.MappingInvalid] if any referenced
     * user or book no longer exists, or if two ABS users map to the same ListenUp user.
     */
    suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit>

    /**
     * Applies confirmed mappings by writing ABS listening progress to the ListenUp database.
     *
     * Uses last-played-wins semantics via `PlaybackPositionRepository.recordPosition`,
     * making re-apply idempotent. Emits [ImportEvent] progress on [observeProgress].
     *
     * Returns [com.calypsan.listenup.api.error.ImportError.ImportNotFound] if the staging
     * directory or `mapping.json` is absent.
     */
    suspend fun apply(importId: ImportId): AppResult<ImportResult>

    /** Returns summaries for all staged ABS import jobs, most recent first. */
    suspend fun listImports(): AppResult<List<ImportSummary>>

    /**
     * Returns the summary for a single staged import job.
     *
     * Returns [com.calypsan.listenup.api.error.ImportError.ImportNotFound] if absent.
     */
    suspend fun getImport(importId: ImportId): AppResult<ImportSummary>

    /**
     * Deletes the staging directory for an import job, including all staged files.
     *
     * Returns [com.calypsan.listenup.api.error.ImportError.ImportNotFound] if absent.
     */
    suspend fun deleteImport(importId: ImportId): AppResult<Unit>

    /**
     * Streams [ImportEvent] progress for the given import job.
     *
     * Admin-gated — returns an empty flow for non-admin callers. Events are emitted
     * during [analyze] and [apply]; the flow completes when the operation finishes or fails.
     */
    fun observeProgress(importId: ImportId): Flow<RpcEvent<ImportEvent>>
}
