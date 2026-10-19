package com.calypsan.listenup.client.presentation.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

/** The reader's own shelves, for the My shelves list (spec §3.2 H24). */
sealed interface MyShelvesUiState {
    /** Before the first read, or while the library is still arriving and no shelf has landed yet. */
    data object Loading : MyShelvesUiState

    /** The shelves, in the order Home shows them; empty means the reader has none yet. */
    data class Ready(
        val shelves: List<Shelf>,
    ) : MyShelvesUiState

    /** The shelves could not be read; [error] says why. */
    data class Error(
        val error: AppError,
    ) : MyShelvesUiState
}

/**
 * Backs the My shelves list on every platform: the same shelf flow Home reads, without the rest of Home.
 *
 * While the library is still arriving on this device (its first population or first catch-up, as Home reads them) an
 * empty list is not yet the reader's, so it stays [MyShelvesUiState.Loading] rather than inviting them to make their
 * first shelf (the same rule Home's section follows).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyShelvesViewModel(
    userRepository: UserRepository,
    private val shelfRepository: ShelfRepository,
    syncRepository: SyncRepository,
) : ViewModel() {
    /**
     * Bumped by [refresh] to re-run a failed read.
     *
     * ⛔ The fallback sits INSIDE the [flatMapLatest], so a failure ends one run rather than the flow the screen is
     * subscribed to (the shape [com.calypsan.listenup.client.presentation.home.HomeStatsViewModel] uses).
     */
    private val readRuns = MutableStateFlow(0)

    private val libraryArriving: Flow<Boolean> =
        combine(syncRepository.buildingInitialLibrary, syncRepository.firstCatchUpRunning) { building, catchingUp ->
            building || catchingUp
        }.distinctUntilChanged()

    /** The list's state, live on Room. */
    val state: StateFlow<MyShelvesUiState> =
        userRepository
            .observeCurrentUser()
            .filterNotNull()
            .distinctUntilChanged { old, new -> old.id == new.id }
            .flatMapLatest { user -> readRuns.flatMapLatest { observeShelves(user.id.value) } }
            .stateIn(viewModelScope, WhileSubscribedForUi, MyShelvesUiState.Loading)

    /** Try again after a failed read. A healthy list is already live on Room and is left alone. */
    fun refresh() {
        if (state.value is MyShelvesUiState.Error) readRuns.update { it + 1 }
    }

    private fun observeShelves(userId: String): Flow<MyShelvesUiState> =
        combine(shelfRepository.observeMyShelves(userId), libraryArriving) { shelves, arriving ->
            if (arriving && shelves.isEmpty()) MyShelvesUiState.Loading else MyShelvesUiState.Ready(shelves)
        }.fallbackTo { e ->
            logger.error(e) { "Error observing my shelves" }
            MyShelvesUiState.Error(ErrorMapper.map(e))
        }
}
