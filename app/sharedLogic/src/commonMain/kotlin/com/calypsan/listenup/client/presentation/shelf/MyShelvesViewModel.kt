package com.calypsan.listenup.client.presentation.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

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
 * While the library is still arriving on this device ([libraryArriving]) an empty list is not yet the reader's, so it
 * stays [MyShelvesUiState.Loading] rather than inviting them to make their first shelf (the same rule Home's section
 * follows).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyShelvesViewModel(
    userRepository: UserRepository,
    shelfRepository: ShelfRepository,
    libraryArriving: Flow<Boolean>,
) : ViewModel() {
    /** The list's state, live on Room. */
    val state: StateFlow<MyShelvesUiState> =
        userRepository
            .observeCurrentUser()
            .filterNotNull()
            .distinctUntilChanged { old, new -> old.id == new.id }
            .flatMapLatest { user ->
                combine(shelfRepository.observeMyShelves(user.id.value), libraryArriving) { shelves, arriving ->
                    if (arriving && shelves.isEmpty()) MyShelvesUiState.Loading else MyShelvesUiState.Ready(shelves)
                }
            }.fallbackTo { e ->
                logger.error(e) { "Error observing my shelves" }
                MyShelvesUiState.Error(ErrorMapper.map(e))
            }.stateIn(viewModelScope, WhileSubscribedForUi, MyShelvesUiState.Loading)
}
