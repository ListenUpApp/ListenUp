package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.SeriesId

/**
 * Lightweight projection of a series as a row the editor lists: a merge-target or parent
 * candidate, or one of the series' own sub-series.
 *
 * [bookCount] is real for a sub-series ([SeriesEditUiState.childSeries]) — every book in it and
 * below it. For a picker candidate it is always `0`: there is no per-series book-count query
 * for the pickers yet, so their dialogs hide it.
 */
data class SeriesCandidate(
    val id: SeriesId,
    val displayName: String,
    val bookCount: Int,
)

/**
 * UI state for series editing screen.
 */
data class SeriesEditUiState(
    // Loading states
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isUploadingCover: Boolean = false,
    val error: String? = null,
    // Series identity
    val seriesId: String = "",
    val name: String = "",
    val description: String = "",
    // Cover management
    val coverPath: String? = null,
    val stagingCoverPath: String? = null,
    val pendingCoverData: ByteArray? = null,
    val pendingCoverFilename: String? = null,
    // Display metadata
    val bookCount: Int = 0,
    // Merge (server-canonical; firehose delivers result)
    val mergeInProgress: Boolean = false,
    // Merge-target picker visibility — candidates are computed only while true
    val mergeDialogVisible: Boolean = false,
    // Merge-target picker query (drives candidate filtering)
    val mergeQuery: String = "",
    /** The parent series' id, or null when this series is a root. */
    val parentId: String? = null,
    /** The parent series' name, for display. */
    val parentName: String? = null,
    /** This series' sub-series, in sibling order, each with the book count of its own subtree. */
    val childSeries: List<SeriesCandidate> = emptyList(),
    /** Whether the parent picker is open — candidates are computed only while true. */
    val parentPickerVisible: Boolean = false,
    /** The parent picker's search text. */
    val parentQuery: String = "",
    /** True while a hierarchy change is on its way to the server. */
    val hierarchyBusy: Boolean = false,
    /** The "New parent series" dialog, while it is open. */
    val newParent: NewSeriesDraft? = null,
    /**
     * Whether the device has a network route. Hierarchy changes need the server, so the screen
     * disables them — and says why — while this is false; everything else still saves.
     */
    val isOnline: Boolean = true,
    // Track if changes have been made
    val hasChanges: Boolean = false,
) {
    /** [description] for the Swift Export boundary: a member named `description` collides with `NSObject.description` and is never exported. */
    val descriptionText: String get() = description

    /**
     * Returns the cover path to display - staging if available, otherwise original.
     */
    val displayCoverPath: String?
        get() = stagingCoverPath ?: coverPath
}

/**
 * Events from the series edit UI.
 */
sealed interface SeriesEditUiEvent {
    /** User edited the series name field. */
    data class NameChanged(
        val name: String,
    ) : SeriesEditUiEvent

    /** User edited the series description field. */
    data class DescriptionChanged(
        val description: String,
    ) : SeriesEditUiEvent

    /** User chose an image to use as the series cover; bytes are staged until Save. */
    data class CoverSelected(
        val imageData: ByteArray,
        val filename: String,
    ) : SeriesEditUiEvent

    data object CoverRemoved : SeriesEditUiEvent

    data object SaveClicked : SeriesEditUiEvent

    data object CancelClicked : SeriesEditUiEvent

    data object ErrorDismissed : SeriesEditUiEvent

    /** User opened the merge-target picker; candidate computation starts. */
    data object MergeDialogOpened : SeriesEditUiEvent

    /** Undo the merge [receiptId] from the "Merged into this" section (#1061). */
    data class UndoMerge(
        val receiptId: MergeReceiptId,
    ) : SeriesEditUiEvent

    /** Read the "Merged into this" list again, after it could not be loaded. */
    data object RetryMergeHistory : SeriesEditUiEvent

    /** User dismissed the merge-target picker; candidates stop computing and the query clears. */
    data object MergeDialogDismissed : SeriesEditUiEvent

    /** User chose to merge the current series into [targetId]. */
    data class MergeInto(
        val targetId: SeriesId,
    ) : SeriesEditUiEvent

    /** The user opened the parent picker. */
    data object ParentPickerOpened : SeriesEditUiEvent

    /** The user closed the parent picker without choosing. */
    data object ParentPickerDismissed : SeriesEditUiEvent

    /** The user typed in the parent picker's search field. */
    data class ParentQueryChanged(
        val query: String,
    ) : SeriesEditUiEvent

    /** The user chose [parentId] as this series' parent. */
    data class ParentSelected(
        val parentId: String,
    ) : SeriesEditUiEvent

    /** The user made this series a root. */
    data object ParentCleared : SeriesEditUiEvent

    /** The user dragged the sub-series into [orderedChildIds]. */
    data class ChildSeriesReordered(
        val orderedChildIds: List<String>,
    ) : SeriesEditUiEvent

    /** The user expanded or collapsed [seriesId]'s sub-series in the "Move into…" tree. */
    data class ParentPickerNodeToggled(
        val seriesId: String,
    ) : SeriesEditUiEvent

    /** The user chose "New parent series…". */
    data object NewParentStarted : SeriesEditUiEvent

    /** The user typed the new parent's name. */
    data class NewParentNameChanged(
        val name: String,
    ) : SeriesEditUiEvent

    /** The user closed the "New parent series" dialog. */
    data object NewParentDismissed : SeriesEditUiEvent

    /** The user chose "Create and move". */
    data object NewParentConfirmed : SeriesEditUiEvent
}

/**
 * Navigation actions from series edit screen.
 */
sealed interface SeriesEditNavAction {
    data object NavigateBack : SeriesEditNavAction

    /**
     * A merge committed; land on [seriesId], the series that survived it.
     *
     * Distinct from [NavigateBack] because a series merge deletes the series being *viewed* — so
     * popping the editor would drop the reader onto the detail page of a series that no longer
     * exists, showing an empty shell and requiring a second Back to escape. The surviving series
     * is the only sensible destination, and it is never the one behind us on the stack.
     */
    data class NavigateToMerged(
        val seriesId: SeriesId,
    ) : SeriesEditNavAction
}
