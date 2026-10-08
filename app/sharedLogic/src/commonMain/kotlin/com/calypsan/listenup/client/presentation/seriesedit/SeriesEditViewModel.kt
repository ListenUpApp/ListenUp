package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.merge.MergeHistory
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.SeriesUpdateRequest
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Maximum number of merge-target candidates surfaced in the picker dialog. */
const val MAX_MERGE_CANDIDATES = 30

/** Idle timeout before stopping merge-candidate collection. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the series edit screen.
 *
 * Handles:
 * - Loading series data for editing
 * - Saving metadata changes
 * - Cover image staging and upload
 * - Server-canonical merge via [SeriesEditRepository]
 * - Placing the series in the hierarchy: its parent, and the order of its sub-series
 * - Tracking unsaved changes
 *
 * @property seriesRepository Repository for loading series data
 * @property updateSeriesUseCase Use case for saving series changes
 * @property imageRepository Repository for persistent cover image operations
 * @property imageStagingRepository Repository for staging cover image operations
 * @property seriesEditRepository RPC dispatcher for merge
 * @property seriesDao DAO for browsing all series as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 * @property networkMonitor Hierarchy changes need the server; offline, the screen disables them
 * @property permissionsRepository Whether the signed-in user may merge and see the merge history (Curate library)
 */
class SeriesEditViewModel internal constructor(
    private val seriesRepository: SeriesRepository,
    private val updateSeriesUseCase: UpdateSeriesUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val seriesEditRepository: SeriesEditRepository,
    seriesDao: SeriesDao,
    private val errorBus: ErrorBus,
    private val networkMonitor: NetworkMonitor,
    private val permissionsRepository: PermissionsRepository,
) : ViewModel() {
    val state: StateFlow<SeriesEditUiState>
        field = MutableStateFlow(SeriesEditUiState())

    /**
     * The merges folded into this series that can still be undone (#1061) — the "Merged into this"
     * section. Read from the server when the series loads — and only with Curate library, since the
     * server refuses the list to anyone without it.
     */
    private val history =
        MergeHistory(
            scope = viewModelScope,
            errorBus = errorBus,
            load = {
                if (permissionsRepository.observeCan(Permission.CURATE_LIBRARY).first()) {
                    seriesEditRepository.listMergeReceipts(SeriesId(state.value.seriesId))
                } else {
                    AppResult.Success(emptyList())
                }
            },
            undo = seriesEditRepository::undoMerge,
        )

    /** What the "Merged into this" section shows. */
    val mergeHistory: StateFlow<MergeHistoryState> = history.state

    private val _navActions = Channel<SeriesEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<SeriesEditNavAction> = _navActions.receiveAsFlow()

    /**
     * Candidates for the merge-target picker — all live series except the current
     * one, filtered by [SeriesEditUiState.mergeQuery] (case-insensitive substring).
     * Capped at [MAX_MERGE_CANDIDATES] to keep the dialog snappy.
     *
     * Computed only while the picker is visible: hidden emits an empty list without ever
     * collecting the DAO, and visible re-filters only when the query or the series table
     * changes — never on unrelated state churn such as typing in the name field, which
     * used to re-scan the whole table on every keystroke.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mergeCandidates: StateFlow<List<SeriesCandidate>> =
        state
            .map { it.mergeDialogVisible }
            .distinctUntilChanged()
            .flatMapLatest { pickerVisible ->
                if (!pickerVisible) {
                    flowOf(emptyList())
                } else {
                    combine(
                        state.map { it.seriesId to it.mergeQuery }.distinctUntilChanged(),
                        seriesDao.observeAll(),
                    ) { (currentId, query), allSeries ->
                        allSeries
                            .asSequence()
                            .filter { it.deletedAt == null }
                            .filter { it.id.value != currentId }
                            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                            .sortedBy { it.name.lowercase() }
                            .take(MAX_MERGE_CANDIDATES)
                            .map { entity ->
                                SeriesCandidate(
                                    id = entity.id,
                                    displayName = entity.name,
                                    bookCount = 0,
                                )
                            }.toList()
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Where the series sits in the hierarchy, the parent picker, and the writes that move it. */
    private val hierarchy =
        SeriesHierarchyEditor(
            scope = viewModelScope,
            errorBus = errorBus,
            state = state,
            observeLineage = seriesRepository::observeSeriesLineage,
            hierarchy = seriesRepository.observeHierarchy(),
            createSeries = seriesEditRepository::createSeries,
            setParent = seriesEditRepository::setParent,
            reorderChildren = seriesEditRepository::reorderChildren,
        )

    /**
     * The "Move into…" picker's rows: the whole tree, with the series itself, everything inside it
     * and its current parent disabled — each with its reason — or every match while searching.
     * Computed only while the picker is visible, like [mergeCandidates].
     */
    val parentPickerRows: StateFlow<List<ParentPickerRow>> = hierarchy.parentPickerRows

    private val subSeries =
        SubSeriesAdder(
            scope = viewModelScope,
            errorBus = errorBus,
            hierarchy = seriesRepository.observeHierarchy(),
            parentId = { state.value.seriesId.ifBlank { null } },
            createSeries = seriesEditRepository::createSeries,
            setParent = seriesEditRepository::setParent,
        )

    /** The "Add sub-series" sheet. */
    val addSubSeries: StateFlow<AddSubSeriesUiState> = subSeries.state

    /** Handle the "Add sub-series" sheet's events. */
    fun onAddSubSeriesEvent(event: AddSubSeriesEvent) {
        subSeries.onEvent(event)
    }

    init {
        viewModelScope.launch {
            networkMonitor.isOnlineFlow.collect { online -> state.update { it.copy(isOnline = online) } }
        }
        permissionsRepository
            .observeCan(Permission.CURATE_LIBRARY)
            .onEach { can -> state.update { it.copy(canCurateLibrary = can) } }
            .launchIn(viewModelScope)
    }

    /**
     * Update the merge-target picker's search query. The [mergeCandidates] Flow
     * re-emits a filtered list whenever this changes.
     */
    fun onMergeQueryChange(query: String) {
        state.update { it.copy(mergeQuery = query) }
    }

    // Track original values for change detection
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var originalCoverPath: String? = null

    /**
     * Load series data for editing.
     */
    fun loadSeries(seriesId: String) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, seriesId = seriesId) }

            val series = seriesRepository.getById(seriesId)
            if (series == null) {
                state.update { it.copy(isLoading = false, error = "Series not found") }
                return@launch
            }
            history.refresh()
            hierarchy.observePlacement(seriesId)

            val bookCount = seriesRepository.getBookIdsForSeries(seriesId).size

            // Get cover path if it exists
            val coverPath =
                if (imageRepository.seriesCoverExists(seriesId)) {
                    imageRepository.getSeriesCoverPath(seriesId)
                } else {
                    null
                }

            // Store original values
            originalName = series.name
            originalDescription = series.description.orEmpty()
            originalCoverPath = coverPath

            state.update { current ->
                current.copy(
                    isLoading = false,
                    name = series.name,
                    description = series.description.orEmpty(),
                    coverPath = coverPath,
                    bookCount = bookCount,
                    hasChanges = false,
                )
            }

            logger.debug { "Loaded series for editing: ${series.name}, bookCount=$bookCount" }
        }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: SeriesEditUiEvent) {
        when (event) {
            is SeriesEditUiEvent.NameChanged -> {
                state.update { it.copy(name = event.name) }
                updateHasChanges()
            }

            is SeriesEditUiEvent.DescriptionChanged -> {
                state.update { it.copy(description = event.description) }
                updateHasChanges()
            }

            is SeriesEditUiEvent.CoverSelected -> {
                handleCoverSelected(event.imageData, event.filename)
            }

            is SeriesEditUiEvent.CoverRemoved -> {
                handleCoverRemoved()
            }

            is SeriesEditUiEvent.SaveClicked -> {
                saveChanges()
            }

            is SeriesEditUiEvent.CancelClicked -> {
                cancelAndCleanup()
            }

            is SeriesEditUiEvent.ErrorDismissed -> {
                state.update { it.copy(error = null) }
            }

            is SeriesEditUiEvent.MergeDialogOpened -> {
                state.update { it.copy(mergeDialogVisible = true) }
            }

            is SeriesEditUiEvent.MergeDialogDismissed -> {
                state.update { it.copy(mergeDialogVisible = false, mergeQuery = "") }
            }

            is SeriesEditUiEvent.MergeInto -> {
                mergeInto(event.targetId)
            }

            is SeriesEditUiEvent.UndoMerge -> {
                history.undo(event.receiptId)
            }

            is SeriesEditUiEvent.RetryMergeHistory -> {
                history.refresh()
            }

            is SeriesEditUiEvent.ParentPickerOpened -> {
                hierarchy.openParentPicker()
            }

            is SeriesEditUiEvent.ParentPickerDismissed -> {
                hierarchy.dismissParentPicker()
            }

            is SeriesEditUiEvent.ParentQueryChanged -> {
                hierarchy.changeParentQuery(event.query)
            }

            is SeriesEditUiEvent.ParentSelected -> {
                hierarchy.changeParent(SeriesId(event.parentId))
            }

            is SeriesEditUiEvent.ParentCleared -> {
                hierarchy.changeParent(null)
            }

            is SeriesEditUiEvent.ChildSeriesReordered -> {
                hierarchy.reorderChildSeries(event.orderedChildIds.map(::SeriesId))
            }

            is SeriesEditUiEvent.ParentPickerNodeToggled -> {
                hierarchy.toggleNode(event.seriesId)
            }

            is SeriesEditUiEvent.NewParentStarted -> {
                hierarchy.startNewParent()
            }

            is SeriesEditUiEvent.NewParentNameChanged -> {
                hierarchy.changeNewParentName(event.name)
            }

            is SeriesEditUiEvent.NewParentDismissed -> {
                hierarchy.dismissNewParent()
            }

            is SeriesEditUiEvent.NewParentConfirmed -> {
                hierarchy.createParent()
            }
        }
    }

    /**
     * Merge the current series into [targetId]. After firehose delivery the source is
     * soft-deleted and all of its books re-point at the target; we navigate back.
     */
    private fun mergeInto(targetId: SeriesId) {
        // The UI hides the control without Curate library; this keeps a stale tap from reaching the server.
        if (!state.value.canCurateLibrary) return
        val sourceId = state.value.seriesId
        if (sourceId.isBlank()) {
            logger.error { "Cannot merge: series ID is empty" }
            return
        }

        viewModelScope.launch {
            // Clear the query along with the flag — a failed merge must not leave a stale
            // pre-filtered query behind the next open (matches the dismiss path).
            state.update {
                it.copy(mergeInProgress = true, mergeDialogVisible = false, mergeQuery = "", error = null)
            }

            when (val result = seriesEditRepository.mergeSeries(SeriesId(sourceId), targetId)) {
                is AppResult.Success -> {
                    state.update { it.copy(mergeInProgress = false) }
                    // The target, not back: this merge soft-deleted the series we were editing.
                    _navActions.trySend(SeriesEditNavAction.NavigateToMerged(targetId))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge series: ${result.message}" }
                    state.update { current ->
                        current.copy(
                            mergeInProgress = false,
                            error =
                                when (result.error) {
                                    is SeriesError.MergeSelfTarget -> "Can't merge a series with itself."
                                    is SeriesError.NotFound -> "One of these series no longer exists."
                                    else -> result.error.message
                                },
                        )
                    }
                }
            }
        }
    }

    /**
     * Update hasChanges flag based on current vs original values.
     */
    private fun updateHasChanges() {
        val current = state.value
        val hasChanges =
            current.name != originalName ||
                current.description != originalDescription ||
                current.pendingCoverData != null // Cover changed if we have pending data

        state.update { it.copy(hasChanges = hasChanges) }
    }

    /**
     * Handle cover selection.
     * Saves the image to a staging location for preview.
     * Does NOT overwrite the main cover until saveChanges() is called.
     */
    private fun handleCoverSelected(
        imageData: ByteArray,
        filename: String,
    ) {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot set cover: series ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isUploadingCover = true, error = null) }

            // Save to staging location for preview (doesn't overwrite original)
            when (val saveResult = imageStagingRepository.saveSeriesCoverStaging(seriesId, imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getSeriesCoverStagingPath(seriesId)
                    logger.info { "Cover saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save Changes is clicked
                    state.update { current ->
                        current.copy(
                            isUploadingCover = false,
                            stagingCoverPath = stagingPath,
                            pendingCoverData = imageData,
                            pendingCoverFilename = filename,
                        )
                    }
                    updateHasChanges()
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save cover to staging: ${saveResult.message}" }
                    state.update { current ->
                        current.copy(
                            isUploadingCover = false,
                            error = "Failed to save cover: ${saveResult.message}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle cover removal.
     * Deletes the staging cover and clears pending data.
     */
    private fun handleCoverRemoved() {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot remove cover: series ID is empty" }
            return
        }

        if (state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }

        state.update { current ->
            current.copy(
                stagingCoverPath = null,
                pendingCoverData = null,
                pendingCoverFilename = null,
            )
        }
        updateHasChanges()

        logger.debug { "Staging cover removed" }
    }

    /**
     * Save all changes via the use case.
     */
    private fun saveChanges() {
        val current = state.value
        if (!current.hasChanges) {
            _navActions.trySend(SeriesEditNavAction.NavigateBack)
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isSaving = true, error = null) }

            val metadataChanged = current.name != originalName || current.description != originalDescription

            val result =
                updateSeriesUseCase(
                    SeriesUpdateRequest(
                        seriesId = current.seriesId,
                        name = current.name,
                        description = current.description,
                        metadataChanged = metadataChanged,
                        nameChanged = current.name != originalName,
                        descriptionChanged = current.description != originalDescription,
                        pendingCoverData = current.pendingCoverData,
                        pendingCoverFilename = current.pendingCoverFilename,
                    ),
                )

            when (result) {
                is AppResult.Success -> {
                    state.update { saving ->
                        saving.copy(
                            isSaving = false,
                            hasChanges = false,
                            pendingCoverData = null,
                            pendingCoverFilename = null,
                            stagingCoverPath = null,
                        )
                    }
                    _navActions.trySend(SeriesEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to save series: ${result.message}" }
                    state.update { it.copy(isSaving = false, error = "Failed to save: ${result.message}") }
                }
            }
        }
    }

    /**
     * Cancel editing and clean up any staging files.
     */
    private fun cancelAndCleanup() {
        val seriesId = state.value.seriesId
        if (seriesId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }
        _navActions.trySend(SeriesEditNavAction.NavigateBack)
    }

    /**
     * Clean up staging files when ViewModel is destroyed.
     * Handles cases where user navigates away without explicitly canceling or saving.
     */
    override fun onCleared() {
        super.onCleared()
        val seriesId = state.value.seriesId
        if (seriesId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }
    }
}
