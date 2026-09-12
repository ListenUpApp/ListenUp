package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.features.books.PickerTarget
import com.calypsan.listenup.web.features.books.SelectionPicker
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * Everything the two "file this book somewhere" pickers need, as one value.
 *
 * A bundle because it is eleven members that always travel together, and because a page signature
 * is a bad place to discover that a feature has that many moving parts.
 */
data class BookPickers(
    val myShelves: List<Shelf> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val onShowShelfPicker: () -> Unit = {},
    val onHideShelfPicker: () -> Unit = {},
    val onAddToShelf: (String) -> Unit = {},
    val onCreateShelfAndAdd: (String) -> Unit = {},
    val onClearShelfError: () -> Unit = {},
    val onShowCollectionPicker: () -> Unit = {},
    val onHideCollectionPicker: () -> Unit = {},
    val onAddToCollection: (String) -> Unit = {},
    val onCreateCollectionAndAdd: (String) -> Unit = {},
    val onClearCollectionError: () -> Unit = {},
)

/**
 * The shelf and collection pickers, and the failures either can report.
 *
 * ⛔ Reuses the very picker multi-select opens over a grid selection ([SelectionPicker]) rather than
 * growing a second one. Filing one book and filing nine are the same question asked about a
 * different number of books — which is why the natives pass `selectedBookCount = 1` here and why
 * this does too, rather than inventing singular copy for a dialog that already says it correctly.
 */
@Composable
fun BookPickerDialogs(
    ready: BookDetailUiState.Ready,
    pickers: BookPickers,
) {
    if (ready.showShelfPicker) {
        SelectionPicker(
            title = "Add to shelf",
            count = 1,
            targets = pickers.myShelves.map { PickerTarget(it.id.value, it.name, null) },
            emptyMessage = "You have no shelves yet.",
            createLabel = "Create shelf",
            isBusy = ready.isAddingToShelf,
            onPick = pickers.onAddToShelf,
            onCreate = pickers.onCreateShelfAndAdd,
            onDismiss = pickers.onHideShelfPicker,
        )
    }

    // ⛔ Gated on isAdmin as well as on the flag, matching Android's own defence-in-depth: the
    // picker must not render for a non-admin even if `showCollectionPicker` were ever set true.
    if (ready.showCollectionPicker && ready.isAdmin) {
        SelectionPicker(
            title = "Add to collection",
            count = 1,
            targets = pickers.collections.map { PickerTarget(it.id, it.name, null) },
            emptyMessage = "There are no collections yet.",
            createLabel = "Create collection",
            isBusy = ready.isAddingToCollection,
            onPick = pickers.onAddToCollection,
            onCreate = pickers.onCreateCollectionAndAdd,
            onDismiss = pickers.onHideCollectionPicker,
        )
    }

    // Errors sit under the header rather than inside the dialog: the dialog closes on a successful
    // pick, so a failure reported only there would vanish with it.
    ready.shelfError?.let { message -> PickerError(message, pickers.onClearShelfError) }
    ready.collectionError?.let { message -> PickerError(message, pickers.onClearCollectionError) }
}

@Composable
private fun PickerError(
    message: String,
    onDismiss: () -> Unit,
) {
    Div(attrs = {
        classes("bd-pick-err")
        attr("role", "status")
        attr("aria-live", "polite")
        onClick { onDismiss() }
    }) { Text(message) }
}
