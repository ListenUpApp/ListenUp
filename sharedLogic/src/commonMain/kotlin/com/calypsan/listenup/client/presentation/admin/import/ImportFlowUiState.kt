package com.calypsan.listenup.client.presentation.admin.import

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.ImportAnalysis
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId

/**
 * Per-phase sealed UI state for the rebuilt Audiobookshelf import flow.
 *
 * The flow progresses linearly: [Idle] → [Uploading] → [Analyzing] → [Review] →
 * [Applying] → [Done]. Any phase can transition to [Error], from which [reset]
 * returns to [Idle]. There are no step-back transitions — this is a destructive
 * pipeline, not a wizard with a back button.
 *
 * Review carries the full [ImportAnalysis] plus the admin's current mapping
 * selections. Only items in [ImportAnalysis.ambiguous] and [ImportAnalysis.unmatched]
 * need manual attention; auto-matched items are applied as-is unless the admin
 * supplies a [bookOverrides] entry. A null value in [bookOverrides] means "skip
 * this item".
 */
sealed interface ImportFlowUiState {
    /** No import in progress; ready to accept a file. */
    data object Idle : ImportFlowUiState

    /** The backup file is being streamed to the server. */
    data class Uploading(
        val filename: String,
    ) : ImportFlowUiState

    /** The server is parsing and matching the backup; live progress is shown. */
    data class Analyzing(
        val done: Int,
        val total: Int,
        val currentItem: String?,
        val usersMatched: Int,
        val booksMatched: Int,
    ) : ImportFlowUiState

    /**
     * Analysis complete; the admin reviews low-confidence / unmatched items before
     * applying. [userMappings] and [bookOverrides] accumulate the admin's selections.
     */
    data class Review(
        val analysis: ImportAnalysis,
        /** Admin-selected ABS-user → ListenUp-user mappings. */
        val userMappings: Map<AbsUserId, UserId>,
        /** Admin-supplied ABS-item → ListenUp-book overrides. Null value = skip. */
        val bookOverrides: Map<AbsItemId, BookId?>,
    ) : ImportFlowUiState

    /** Confirmed mappings are being applied; live progress is shown. */
    data class Applying(
        val done: Int,
        val total: Int,
        val currentItem: String?,
        val sessionsWritten: Int,
    ) : ImportFlowUiState

    /** Import completed; carries the server's outcome. */
    data class Done(
        val result: ImportResult,
    ) : ImportFlowUiState

    /** A phase failed; [message] is a user-facing explanation. */
    data class Error(
        val message: String,
    ) : ImportFlowUiState
}
