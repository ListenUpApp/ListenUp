package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the Create/Edit Shelf screen.
 *
 * Thin presentation coordinator delegating to use cases:
 * - [CreateShelfUseCase] / [UpdateShelfUseCase] for save (routed on mode)
 * - [DeleteShelfUseCase] for destructive removal
 *
 * The name/description text inputs are owned by the screen (Compose
 * `rememberSaveable`), not this ViewModel — the screen passes current
 * values into [save]. Navigation signals are emitted via a
 * `Channel<CreateEditShelfNavAction>` per the one-shot-events rubric rule.
 */
class CreateEditShelfViewModel(
    private val createShelfUseCase: CreateShelfUseCase,
    private val updateShelfUseCase: UpdateShelfUseCase,
    private val deleteShelfUseCase: DeleteShelfUseCase,
    private val shelfRepository: ShelfRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<CreateEditShelfUiState>
        field = MutableStateFlow<CreateEditShelfUiState>(CreateEditShelfUiState.Idle)

    private val _navActions = Channel<CreateEditShelfNavAction>(Channel.BUFFERED)
    val navActions: Flow<CreateEditShelfNavAction> = _navActions.receiveAsFlow()

    private var editingShelfId: ShelfId? = null

    /** Prepare for creating a new shelf. */
    fun initCreate() {
        editingShelfId = null
        state.value = CreateEditShelfUiState.Idle
    }

    /** Prepare for editing an existing shelf by id. Fetches the current data. */
    fun initEdit(shelfId: String) {
        val id = ShelfId(shelfId)
        editingShelfId = id
        viewModelScope.launch {
            state.value = CreateEditShelfUiState.LoadingExisting

            state.value =
                try {
                    val shelf = shelfRepository.getById(id)
                    if (shelf != null) {
                        CreateEditShelfUiState.Loaded(
                            name = shelf.name,
                            description = shelf.description ?: "",
                            isPrivate = shelf.isPrivate,
                        )
                    } else {
                        CreateEditShelfUiState.Error("Shelf not found")
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorBus.emit(ErrorMapper.map(e))
                    logger.error(e) { "Failed to load shelf for edit: $shelfId" }
                    CreateEditShelfUiState.Error("Failed to load shelf: ${e.message}")
                }
        }
    }

    /**
     * Save the shelf with the provided values. Routes to create or update
     * based on whether [initEdit] was called. Emits [CreateEditShelfNavAction.NavigateBack]
     * on success.
     */
    fun save(
        name: String,
        description: String,
        isPrivate: Boolean,
    ) {
        viewModelScope.launch {
            state.value = CreateEditShelfUiState.Saving
            val editingId = editingShelfId
            val trimmedDescription = description.takeIf { it.isNotEmpty() }

            val result =
                if (editingId != null) {
                    updateShelfUseCase(
                        shelfId = editingId,
                        name = name,
                        description = trimmedDescription,
                        isPrivate = isPrivate,
                    )
                } else {
                    createShelfUseCase(
                        name = name,
                        description = trimmedDescription,
                        isPrivate = isPrivate,
                    )
                }

            when (result) {
                is AppResult.Success -> {
                    _navActions.trySend(CreateEditShelfNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    state.value = CreateEditShelfUiState.Error(result.message)
                }
            }
        }
    }

    /** Delete the shelf being edited. No-op in create mode. */
    fun delete() {
        val shelfId = editingShelfId ?: return

        viewModelScope.launch {
            state.value = CreateEditShelfUiState.Saving

            when (val result = deleteShelfUseCase(shelfId)) {
                is AppResult.Success -> {
                    _navActions.trySend(CreateEditShelfNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    state.value = CreateEditShelfUiState.Error(result.message)
                }
            }
        }
    }

    /** Dismiss any error and return to [CreateEditShelfUiState.Idle]. */
    fun dismissError() {
        if (state.value is CreateEditShelfUiState.Error) {
            state.value = CreateEditShelfUiState.Idle
        }
    }
}

/**
 * UI state for the Create/Edit Shelf screen.
 *
 * Sealed hierarchy — the VM is always in exactly one of these states.
 * Text inputs (name, description) are held by the Compose layer, not this state.
 */
sealed interface CreateEditShelfUiState {
    /** Ready for input; the screen's rememberSaveable holds the text. */
    data object Idle : CreateEditShelfUiState

    /** Edit mode — fetching existing shelf data. */
    data object LoadingExisting : CreateEditShelfUiState

    /** Edit mode — existing data available for the screen to seed once into its inputs. */
    data class Loaded(
        val name: String,
        val description: String,
        val isPrivate: Boolean,
    ) : CreateEditShelfUiState

    /** A save or delete operation is in flight. */
    data object Saving : CreateEditShelfUiState

    /** Operation failed. Dismissing returns to [Idle]. */
    data class Error(
        val message: String,
    ) : CreateEditShelfUiState
}

/** Navigation events emitted by [CreateEditShelfViewModel]. */
sealed interface CreateEditShelfNavAction {
    /** Save or delete succeeded — screen should pop back. */
    data object NavigateBack : CreateEditShelfNavAction
}
