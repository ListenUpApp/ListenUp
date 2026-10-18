package com.calypsan.listenup.web.features.shelf

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.client.presentation.shelf.MyShelvesViewModel
import com.calypsan.listenup.web.nav.RouteSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open My shelves list: its state and the teardown. The list has no actions of its own — opening a shelf and
 * starting a new one are navigation, decided by the route.
 */
class MyShelvesSession(
    val state: StateFlow<MyShelvesUiState>,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session. */
typealias OpenMyShelves = () -> MyShelvesSession

/** The production source: the shared [MyShelvesViewModel] over the started graph. */
fun graphMyShelves(koin: Koin): OpenMyShelves =
    {
        val viewModel = koin.get<MyShelvesViewModel>()
        val store = ViewModelStore().apply { put(MY_SHELVES_STORE_KEY, viewModel) }
        MyShelvesSession(state = viewModel.state, close = store::clear)
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedMyShelves(state: MyShelvesUiState = MyShelvesUiState.Loading): OpenMyShelves =
    { MyShelvesSession(state = MutableStateFlow(state), close = {}) }

private const val MY_SHELVES_STORE_KEY = "my-shelves"
