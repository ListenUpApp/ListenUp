package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowViewModel
import com.calypsan.listenup.core.AbsUserId

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
 * The whole Review snapshot, flattened for Swift: the per-ABS-user rows plus the running counts
 * the Review header renders. The picker's ListenUp users ride through SKIE cleanly already
 * (`AdminUserInfo` has String ids), so the Swift observer maps those directly.
 */
data class ImportReviewSnapshot(
    val users: List<ImportReviewUserSnapshot>,
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

        return ImportReviewSnapshot(
            users = users,
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
