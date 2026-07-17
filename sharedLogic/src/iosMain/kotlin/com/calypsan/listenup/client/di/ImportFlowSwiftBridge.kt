package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId

/**
 * One ABS user in the import Review step, flattened to plain Swift-consumable strings.
 *
 * SKIE unboxes a value class to a `String` only at a *direct, non-collection* parameter or
 * return position. The Review state holds `AbsUserId` / `UserId` inside `data class` fields and
 * `Map`/`Set` collections, so SKIE boxes them to opaque `id` objects Swift can't read. This holder
 * is produced Kotlin-side (where the value classes are in scope) so the Swift Review screen never
 * touches a boxed value class.
 *
 * [resolution] is one of [ImportFlowSwiftBridge.RESOLUTION_ASSIGNED],
 * [ImportFlowSwiftBridge.RESOLUTION_SKIPPED], or [ImportFlowSwiftBridge.RESOLUTION_NEEDS_REVIEW].
 * [assignedUserId] is the assigned ListenUp user id when assigned, else null.
 */
data class ImportReviewUserSnapshot(
    val absUserId: String,
    val username: String,
    val email: String?,
    val suggestedUserId: String?,
    val resolution: String,
    val assignedUserId: String?,
)

/**
 * One ambiguous/unmatched ABS book item in the Review step, flattened to plain Swift strings.
 *
 * Mirrors [ImportReviewUserSnapshot] for books: the value-class ids (`AbsItemId`, `BookId`) live
 * inside the Review state's `List<AbsItemRef>` / `Map<AbsItemId, BookId?>`, where SKIE boxes them,
 * so this holder is produced Kotlin-side. [resolution] is one of the `RESOLUTION_*` markers;
 * [assignedBookId] is the chosen ListenUp book id when assigned, else null. [isUnmatched]
 * distinguishes an unmatched item from a low-confidence ambiguous one for the row's tier label.
 */
data class ImportReviewBookSnapshot(
    val absItemId: String,
    val title: String,
    val asin: String?,
    val isbn: String?,
    val isUnmatched: Boolean,
    val resolution: String,
    val assignedBookId: String?,
)

/** One book-search result row, flattened for Swift (the domain `BookSearchHit.bookId` is boxed). */
data class ImportBookSearchHitSnapshot(
    val bookId: String,
    val title: String,
    val author: String,
)

/**
 * The open book-search panel, flattened for Swift, or absent when no panel is open. [absItemId] is
 * the item being resolved; [results] is empty while [query] is blank or a search is in flight.
 */
data class ImportBookSearchSnapshot(
    val absItemId: String,
    val query: String,
    val isSearching: Boolean,
    val results: List<ImportBookSearchHitSnapshot>,
)

/**
 * The whole Review snapshot, flattened for Swift: the per-ABS-user rows and the per-book review
 * rows, the open book-search panel (if any), plus the running counts the Review header renders. The
 * picker's ListenUp users ride through SKIE cleanly already (`AdminUserInfo` has String ids), so the
 * Swift observer maps those directly.
 */
data class ImportReviewSnapshot(
    val users: List<ImportReviewUserSnapshot>,
    val books: List<ImportReviewBookSnapshot>,
    val bookSearch: ImportBookSearchSnapshot?,
    val autoMatchedCount: Int,
    val booksMatchedCount: Int,
    val ambiguousCount: Int,
    val unmatchedCount: Int,
    val importableSessionCount: Int,
)

/**
 * Swift-facing bridge over [ImportFlowViewModel]'s value-class-typed user-mapping API.
 *
 * The wizard VM speaks `AbsUserId` / `UserId` value classes for type safety; SKIE boxes those
 * inside the Review state's collections and `data class` fields (see [ImportReviewUserSnapshot]).
 * This object is the thin seam that keeps both sides clean: Swift passes plain `String`s for
 * actions, and reads a fully-flattened [ImportReviewSnapshot] for rendering. The VM itself is
 * unchanged — this only adapts its surface for Swift.
 */
object ImportFlowSwiftBridge {
    /** Assign [absUserId] to ListenUp user [listenUpUserId] (both plain ids). */
    fun assignUser(
        viewModel: ImportFlowViewModel,
        absUserId: String,
        listenUpUserId: String,
    ) {
        viewModel.setUserMapping(AbsUserId(absUserId), UserId(listenUpUserId))
    }

    /** Mark [absUserId] as explicitly skipped. */
    fun skipUser(
        viewModel: ImportFlowViewModel,
        absUserId: String,
    ) {
        viewModel.skipUser(AbsUserId(absUserId))
    }

    /** Open the book-search panel for [absItemId] (plain id). */
    fun openBookSearch(
        viewModel: ImportFlowViewModel,
        absItemId: String,
    ) {
        viewModel.openBookSearch(AbsItemId(absItemId))
    }

    /** Close the open book-search panel and cancel any in-flight search. */
    fun closeBookSearch(viewModel: ImportFlowViewModel) {
        viewModel.closeBookSearch()
    }

    /** Update the book-search query, triggering a debounced search. */
    fun updateBookSearchQuery(
        viewModel: ImportFlowViewModel,
        query: String,
    ) {
        viewModel.updateBookSearchQuery(query)
    }

    /** Assign [bookId] to [absItemId] (both plain ids) and close the search panel. */
    fun selectBook(
        viewModel: ImportFlowViewModel,
        absItemId: String,
        bookId: String,
    ) {
        viewModel.selectBook(AbsItemId(absItemId), BookId(bookId))
    }

    /** Mark [absItemId] as explicitly skipped (import no history for it). */
    fun skipBook(
        viewModel: ImportFlowViewModel,
        absItemId: String,
    ) {
        viewModel.skipBook(AbsItemId(absItemId))
    }

    /**
     * Flatten the Review portion of [state] into a Swift-consumable [ImportReviewSnapshot], or
     * null when the flow is not in [ImportFlowUiState.Review]. Reads the boxed value-class
     * collections here, where the Kotlin types are in scope.
     */
    fun reviewSnapshot(state: ImportFlowUiState): ImportReviewSnapshot? {
        val review = state as? ImportFlowUiState.Review ?: return null
        val analysis = review.analysis

        val users =
            analysis.userMatches.map { match ->
                val absId = match.absUserId.value
                val assigned = review.userMappings[match.absUserId]
                val resolution =
                    when {
                        assigned != null -> RESOLUTION_ASSIGNED
                        review.skippedUsers.contains(match.absUserId) -> RESOLUTION_SKIPPED
                        else -> RESOLUTION_NEEDS_REVIEW
                    }
                ImportReviewUserSnapshot(
                    absUserId = absId,
                    username = match.absUsername,
                    email = match.absEmail,
                    suggestedUserId = match.suggestedUserId?.value,
                    resolution = resolution,
                    assignedUserId = assigned?.value,
                )
            }

        // Ambiguous + unmatched items are the only ones surfaced for manual attention; confident
        // auto-matches are applied server-side and never appear here (mirrors the Android review).
        val ambiguousBooks = analysis.ambiguous.map { it to false }
        val unmatchedBooks = analysis.unmatched.map { it to true }
        val books =
            (ambiguousBooks + unmatchedBooks).map { (item, isUnmatched) ->
                val hasOverride = review.bookOverrides.containsKey(item.absItemId)
                val override = review.bookOverrides[item.absItemId]
                val resolution =
                    when {
                        !hasOverride -> RESOLUTION_NEEDS_REVIEW
                        override != null -> RESOLUTION_ASSIGNED
                        else -> RESOLUTION_SKIPPED
                    }
                ImportReviewBookSnapshot(
                    absItemId = item.absItemId.value,
                    title = item.title,
                    asin = item.asin,
                    isbn = item.isbn,
                    isUnmatched = isUnmatched,
                    resolution = resolution,
                    assignedBookId = override?.value,
                )
            }

        val bookSearch =
            review.bookSearch?.let { search ->
                ImportBookSearchSnapshot(
                    absItemId = search.absItemId.value,
                    query = search.query,
                    isSearching = search.isSearching,
                    results =
                        search.results.map { hit ->
                            ImportBookSearchHitSnapshot(
                                bookId = hit.bookId.value,
                                title = hit.title,
                                author = hit.author,
                            )
                        },
                )
            }

        val autoMatchedCount =
            analysis.bookMatchCounts
                .filterKeys { it != MatchTier.AMBIGUOUS && it != MatchTier.UNMATCHED }
                .values
                .sum()

        return ImportReviewSnapshot(
            users = users,
            books = books,
            bookSearch = bookSearch,
            autoMatchedCount = autoMatchedCount,
            booksMatchedCount = analysis.bookMatchCounts.values.sum(),
            ambiguousCount = analysis.ambiguous.size,
            unmatchedCount = analysis.unmatched.size,
            importableSessionCount = analysis.importableSessionCount,
        )
    }

    /** Resolution marker: the ABS user is assigned to a ListenUp user. */
    const val RESOLUTION_ASSIGNED: String = "assigned"

    /** Resolution marker: the ABS user is explicitly skipped. */
    const val RESOLUTION_SKIPPED: String = "skipped"

    /** Resolution marker: the ABS user still needs the admin's decision. */
    const val RESOLUTION_NEEDS_REVIEW: String = "needs_review"
}
