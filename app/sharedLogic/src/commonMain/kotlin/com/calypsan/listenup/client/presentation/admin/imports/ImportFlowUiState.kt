package com.calypsan.listenup.client.presentation.admin.imports

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId

/**
 * A lightweight projection of a library book returned by the book-search panel.
 *
 * Carries only what the panel needs to render a result row and let the admin pick.
 * Mapped from [com.calypsan.listenup.client.domain.model.SearchHit] at the ViewModel layer.
 */
data class BookSearchHit(
    val bookId: BookId,
    val title: String,
    val author: String,
)

/**
 * State for the book-search panel attached to one ABS item in [ImportFlowUiState.Review].
 *
 * Opened by [ImportFlowViewModel.openBookSearch], closed by [ImportFlowViewModel.closeBookSearch]
 * or [ImportFlowViewModel.selectBook]. [isSearching] is true while the async search call is in
 * flight. [results] is empty when [query] is blank or when the search has not yet returned.
 */
data class BookSearchState(
    /** ABS item this panel is resolving. */
    val absItemId: AbsItemId,
    /** Current search query typed by the admin. */
    val query: String,
    /** Search results mapped from the SearchRepository. Empty when [query] is blank. */
    val results: List<BookSearchHit>,
    /** True while the repository call is in flight. */
    val isSearching: Boolean,
)

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
     * An ambiguous/unmatched ABS item is "resolved" iff present in [bookOverrides] —
     * assigned to a [BookId] value, or null-valued (= skip). Confident auto-matches are
     * applied server-side and are absent from [ImportAnalysis.ambiguous]/[ImportAnalysis.unmatched];
     * they never appear here.
     *
     * [listenupUsers] is the full list of ListenUp users available for the picker. It may
     * be empty if the admin-user-list load failed (graceful degradation — the admin can still
     * skip users; the picker simply shows nothing to pick from).
     *
     * [bookSearch] is non-null when the book-search panel is open for a specific ABS item.
     * It is set by [ImportFlowViewModel.openBookSearch] and cleared by
     * [ImportFlowViewModel.closeBookSearch] or [ImportFlowViewModel.selectBook].
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
        /**
         * State of the book-search panel, or null when no panel is open.
         * Only one panel can be open at a time — opening a new one implicitly closes the previous.
         */
        val bookSearch: BookSearchState? = null,
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

    /** A phase failed; [error] is the typed cause, rendered via `AppError.localized()`. */
    data class Error(
        val error: AppError,
    ) : ImportFlowUiState
}
