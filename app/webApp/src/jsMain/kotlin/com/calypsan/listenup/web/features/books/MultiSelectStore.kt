package com.calypsan.listenup.web.features.books

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open multi-select session over the library grid.
 *
 * ⛔ The selection lives in the ViewModel, not in the page. Every surface that can act on a
 * selection — the bar, the pickers, the create-and-add paths — reads the same set, so "what is
 * selected" has exactly one answer rather than one per component.
 */
@Suppress("LongParameterList")
class MultiSelectSession(
    val selectionMode: StateFlow<SelectionMode>,
    val adminAccess: StateFlow<Boolean>,
    val collections: StateFlow<List<Collection>>,
    val myShelves: StateFlow<List<Shelf>>,
    val addingToShelf: StateFlow<Boolean>,
    val addingToCollection: StateFlow<Boolean>,
    val events: Flow<BookMultiSelectEvent>,
    val onEnter: () -> Unit,
    val onToggle: (String) -> Unit,
    val onExit: () -> Unit,
    val onSelectAll: (List<String>) -> Unit,
    val onDeselectAll: () -> Unit,
    val onAddToShelf: (String) -> Unit,
    val onCreateShelfAndAdd: (String) -> Unit,
    val onAddToCollection: (String) -> Unit,
    val onCreateCollectionAndAdd: (String) -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the library gets its selection. Production resolves the real ViewModel; specs hand one over. */
typealias OpenMultiSelect = () -> MultiSelectSession

/** The production source: the shared [BookMultiSelectViewModel]. */
fun graphMultiSelect(koin: Koin): OpenMultiSelect =
    {
        val viewModel = koin.get<BookMultiSelectViewModel>()
        val store = ViewModelStore().apply { put("multiSelect", viewModel) }
        MultiSelectSession(
            selectionMode = viewModel.selectionMode,
            adminAccess = viewModel.adminAccess,
            collections = viewModel.collections,
            myShelves = viewModel.myShelves,
            addingToShelf = viewModel.addingToShelf,
            addingToCollection = viewModel.addingToCollection,
            events = viewModel.events,
            onEnter = viewModel::enterSelectionMode,
            onToggle = viewModel::toggleSelection,
            onExit = viewModel::exitSelectionMode,
            onSelectAll = viewModel::selectAll,
            onDeselectAll = viewModel::deselectAll,
            onAddToShelf = viewModel::addSelectedToShelf,
            onCreateShelfAndAdd = viewModel::createShelfAndAddBooks,
            onAddToCollection = viewModel::addSelectedToCollection,
            onCreateCollectionAndAdd = viewModel::createCollectionAndAddBooks,
            close = store::clear,
        )
    }

/** The ids currently selected, or empty when selection is not active. */
internal fun SelectionMode.selectedIds(): Set<String> = (this as? SelectionMode.Active)?.selectedIds.orEmpty()

/** Whether the reader is picking books right now. */
internal fun SelectionMode.isActive(): Boolean = this is SelectionMode.Active
