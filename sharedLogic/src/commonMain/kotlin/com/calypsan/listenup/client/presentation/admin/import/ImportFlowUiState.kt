package com.calypsan.listenup.client.presentation.admin.import

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.ImportAnalysis
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
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
     * Analysis complete; the admin reviews each ABS user and low-confidence / unmatched
     * book items before applying. The admin must EXPLICITLY assign or skip each ABS user.
     *
     * A user is "resolved" iff present in [userMappings] (assigned) OR [skippedUsers]
     * (skipped). Unresolved users are treated as skipped — no history is imported for them.
     * [bookOverrides] accumulates admin-supplied book-match overrides; null value = skip.
     *
     * [listenupUsers] is the full list of ListenUp users available for the picker. It may
     * be empty if the admin-user-list load failed (graceful degradation — the admin can still
     * skip users; the picker simply shows nothing to pick from).
     */
    data class Review(
        val analysis: ImportAnalysis,
        /**
         * Admin-selected ABS-user → ListenUp-user mappings. Starts empty; the admin assigns
         * each ABS user explicitly. [setUserMapping] also removes the user from [skippedUsers].
         */
        val userMappings: Map<AbsUserId, UserId>,
        /**
         * ABS users the admin explicitly chose to skip. Starts empty. [skipUser] also removes
         * any existing [userMappings] entry for the same ABS user.
         */
        val skippedUsers: Set<AbsUserId>,
        /** Admin-supplied ABS-item → ListenUp-book overrides. Null value = skip. */
        val bookOverrides: Map<AbsItemId, BookId?>,
        /**
         * ListenUp users available for the user picker. Populated from [AdminRepository.getUsers]
         * when entering Review; empty on load failure (non-fatal).
         */
        val listenupUsers: List<AdminUserInfo>,
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
