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
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.toNotice
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import com.calypsan.listenup.client.presentation.edit.EditPhase
import com.calypsan.listenup.client.presentation.edit.FormSlot
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

private val logger = KotlinLogging.logger {}

/** Maximum number of merge-target candidates surfaced in the picker dialog. */
const val MAX_MERGE_CANDIDATES = 30

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
 * @param networkMonitor Hierarchy changes need the server; offline, the screen disables them
 * @param permissionsRepository Whether the signed-in user may merge and see the merge history (Curate library)
 */
class SeriesEditViewModel internal constructor(
    private val seriesRepository: SeriesRepository,
    private val updateSeriesUseCase: UpdateSeriesUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val seriesEditRepository: SeriesEditRepository,
    seriesDao: SeriesDao,
    private val errorBus: ErrorBus,
    networkMonitor: NetworkMonitor,
    permissionsRepository: PermissionsRepository,
) : ViewModel() {
    val state: StateFlow<SeriesEditUiState>
        field = MutableStateFlow<SeriesEditUiState>(SeriesEditUiState.Loading)

    /** The form inside [SeriesEditUiState.Editing]; edits outside it are dropped. */
    private val form: FormSlot<SeriesEditUiState, SeriesEditForm> =
        FormSlot(
            state = state,
            formOf = { (it as? SeriesEditUiState.Editing)?.form },
            withForm = {
                current,
                form,
                ->
                if (current is SeriesEditUiState.Editing) current.copy(form = form) else current
            },
        )

    /** Whether hierarchy changes can reach the server; held live so a form built later starts with it. */
    private val onlineStatus: StateFlow<Boolean> =
        networkMonitor.onlineFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** May merge and see the merge history (Curate library); held live for the same reason. */
    private val canCurateLibrary: StateFlow<Boolean> =
        permissionsRepository
            .observeCan(Permission.CURATE_LIBRARY)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
                    seriesEditRepository.listMergeReceipts(SeriesId(form.value?.seriesId.orEmpty()))
                } else {
                    AppResult.Success(emptyList())
                }
            },
            undo = seriesEditRepository::undoMerge,
        )

    /** What the "Merged into this" section shows. */
    val mergeHistory: StateFlow<MergeHistoryState> = history.state

    private val navActionsChannel = Channel<SeriesEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<SeriesEditNavAction> = navActionsChannel.receiveAsFlow()

    /**
     * Candidates for the merge-target picker — all live series except the current
     * one, filtered by [SeriesEditForm.mergeQuery] (case-insensitive substring).
     * Capped at [MAX_MERGE_CANDIDATES] to keep the dialog snappy.
     *
     * Computed only while the picker is visible: hidden emits an empty list without ever
     * collecting the DAO, and visible re-filters only when the query or the series table
     * changes — never on unrelated state churn such as typing in the name field, which
     * used to re-scan the whole table on every keystroke.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mergeCandidates: StateFlow<List<SeriesCandidate>> =
        form.flow
            .map { it?.mergeDialogVisible == true }
            .distinctUntilChanged()
            .flatMapLatest { pickerVisible ->
                if (!pickerVisible) {
                    flowOf(emptyList())
                } else {
                    combine(
                        form.flow.map { it?.seriesId to it?.mergeQuery.orEmpty() }.distinctUntilChanged(),
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
            }.stateIn(viewModelScope, WhileSubscribedForUi, emptyList())

    /** Where the series sits in the hierarchy, the parent picker, and the writes that move it. */
    private val hierarchy =
        SeriesHierarchyEditor(
            scope = viewModelScope,
            errorBus = errorBus,
            state = form,
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
            parentId = { form.value?.run { seriesId.ifBlank { null } } },
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
        onlineStatus.onEach { online -> form.update { it.copy(isOnline = online) } }.launchIn(viewModelScope)
        canCurateLibrary.onEach { can -> form.update { it.copy(canCurateLibrary = can) } }.launchIn(viewModelScope)
    }

    /**
     * Update the merge-target picker's search query. The [mergeCandidates] Flow
     * re-emits a filtered list whenever this changes.
     */
    fun onMergeQueryChange(query: String) {
        form.update { it.copy(mergeQuery = query) }
    }

    /** The id whose data the form currently holds or is fetching; a repeat load for it is a no-op. */
    private var loadedSeriesId: String? = null
    private var loadJob: Job? = null

    /**
     * Load series data for editing.
     */
    fun loadSeries(seriesId: String) {
        // The screen asks again whenever it is recreated (rotation, theme, fold). The form already
        // holds this series, possibly with unsaved edits, so there is nothing to fetch.
        if (loadedSeriesId == seriesId) return
        loadedSeriesId = seriesId

        loadJob?.cancel()
        state.value = SeriesEditUiState.Loading
        loadJob =
            viewModelScope.launch {
                val series = seriesRepository.getById(seriesId)
                if (series == null) {
                    // Let the same id be asked for again: nothing was loaded.
                    loadedSeriesId = null
                    state.value = SeriesEditUiState.LoadFailed(seriesId, SeriesError.NotFound())
                    return@launch
                }

                val bookCount = seriesRepository.getBookIdsForSeries(seriesId).size

                // Get cover path if it exists
                val coverPath =
                    if (imageRepository.seriesCoverExists(seriesId)) {
                        imageRepository.getSeriesCoverPath(seriesId)
                    } else {
                        // Missing locally but present on the server: fetch it for the next open.
                        if (series.coverPath != null) imageRepository.ensureSeriesCoverCached(seriesId)
                        null
                    }

                val loaded =
                    SeriesEditForm(
                        seriesId = seriesId,
                        name = series.name,
                        description = series.description.orEmpty(),
                        coverPath = coverPath,
                        bookCount = bookCount,
                        isOnline = onlineStatus.value,
                        canCurateLibrary = canCurateLibrary.value,
                    )
                state.value = SeriesEditUiState.Editing(form = loaded, original = loaded)
                history.refresh()
                hierarchy.observePlacement(seriesId)

                logger.debug { "Loaded series for editing: ${series.name}, bookCount=$bookCount" }
            }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: SeriesEditUiEvent) {
        when (event) {
            is SeriesEditUiEvent.NameChanged -> {
                form.update { it.copy(name = event.name) }
            }

            is SeriesEditUiEvent.DescriptionChanged -> {
                form.update { it.copy(description = event.description) }
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
                updateEditing { it.copy(inlineError = null) }
            }

            is SeriesEditUiEvent.MergeDialogOpened -> {
                form.update { it.copy(mergeDialogVisible = true) }
            }

            is SeriesEditUiEvent.MergeDialogDismissed -> {
                form.update { it.copy(mergeDialogVisible = false, mergeQuery = "") }
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

    /** The [SeriesEditUiState.Editing] the screen is on, if it is editing and nothing is in flight. */
    private fun idleEditing(): SeriesEditUiState.Editing? =
        (state.value as? SeriesEditUiState.Editing)?.takeIf { it.phase == EditPhase.Idle }

    private inline fun updateEditing(transform: (SeriesEditUiState.Editing) -> SeriesEditUiState.Editing) {
        state.update { if (it is SeriesEditUiState.Editing) transform(it) else it }
    }

    /**
     * Merge the current series into [targetId]. After firehose delivery the source is
     * soft-deleted and all of its books re-point at the target; we navigate back.
     */
    private fun mergeInto(targetId: SeriesId) {
        val editing = idleEditing() ?: return
        // The UI hides the control without Curate library; this keeps a stale tap from reaching the server.
        if (!editing.form.canCurateLibrary) return
        val sourceId = SeriesId(editing.form.seriesId)
        // Clear the query along with the picker — a failed merge must not leave a stale pre-filtered
        // query behind the next open (matches the dismiss path).
        state.value =
            editing.copy(
                phase = EditPhase.Merging,
                inlineError = null,
                form = editing.form.copy(mergeDialogVisible = false, mergeQuery = ""),
            )

        viewModelScope.launch {
            when (val result = seriesEditRepository.mergeSeries(sourceId, targetId)) {
                is AppResult.Success -> {
                    updateEditing { it.copy(phase = EditPhase.Idle) }
                    // The target, not back: this merge soft-deleted the series we were editing.
                    navActionsChannel.trySend(SeriesEditNavAction.NavigateToMerged(targetId))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge series: ${result.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
                }
            }
        }
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
        val editing = idleEditing() ?: return
        val seriesId = editing.form.seriesId
        state.value = editing.copy(phase = EditPhase.StagingImage, inlineError = null)

        viewModelScope.launch {
            // Save to staging location for preview (doesn't overwrite original)
            when (val saveResult = imageStagingRepository.saveSeriesCoverStaging(seriesId, imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getSeriesCoverStagingPath(seriesId)
                    logger.info { "Cover saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save Changes is clicked
                    updateEditing { current ->
                        current.copy(
                            phase = EditPhase.Idle,
                            form =
                                current.form.copy(
                                    stagingCoverPath = stagingPath,
                                    pendingCoverData = imageData,
                                    pendingCoverFilename = filename,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save cover to staging: ${saveResult.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = saveResult.error) }
                }
            }
        }
    }

    /**
     * Handle cover removal.
     * Deletes the staging cover and clears pending data.
     */
    private fun handleCoverRemoved() {
        val current = form.value ?: return
        if (current.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(current.seriesId)
        }

        form.update { staged ->
            staged.copy(
                stagingCoverPath = null,
                pendingCoverData = null,
                pendingCoverFilename = null,
            )
        }

        logger.debug { "Staging cover removed" }
    }

    /**
     * Save all changes via the use case.
     *
     * Refused unless the form is idle, so a second tap while the first Save is in flight is a no-op.
     * A failure stays on the form as [SeriesEditUiState.Editing.inlineError], edits intact.
     */
    private fun saveChanges() {
        val editing = idleEditing() ?: return
        if (!editing.hasChanges) {
            navActionsChannel.trySend(SeriesEditNavAction.NavigateBack)
            return
        }
        state.value = editing.copy(phase = EditPhase.Saving, inlineError = null)
        val current = editing.form
        val original = editing.original

        viewModelScope.launch {
            val nameChanged = current.name != original.name
            val descriptionChanged = current.description != original.description

            val result =
                updateSeriesUseCase(
                    SeriesUpdateRequest(
                        seriesId = current.seriesId,
                        name = current.name,
                        description = current.description,
                        metadataChanged = nameChanged || descriptionChanged,
                        nameChanged = nameChanged,
                        descriptionChanged = descriptionChanged,
                        pendingCoverData = current.pendingCoverData,
                        pendingCoverFilename = current.pendingCoverFilename,
                    ),
                )

            when (result) {
                is AppResult.Success -> {
                    // The save went through; a cover that did not reach the server is said out loud.
                    (result.data as? SaveOutcome.SavedImageNotUploaded)?.let { errorBus.emit(it.toNotice()) }
                    updateEditing { saving ->
                        val saved =
                            saving.form.copy(
                                pendingCoverData = null,
                                pendingCoverFilename = null,
                                stagingCoverPath = null,
                            )
                        saving.copy(phase = EditPhase.Idle, form = saved, original = saved)
                    }
                    navActionsChannel.trySend(SeriesEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to save series: ${result.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
                }
            }
        }
    }

    /**
     * Cancel editing and clean up any staging files.
     */
    private fun cancelAndCleanup() {
        cleanUpStagedCover()
        navActionsChannel.trySend(SeriesEditNavAction.NavigateBack)
    }

    private fun cleanUpStagedCover() {
        val current = form.value ?: return
        if (current.seriesId.isNotBlank() && current.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(current.seriesId)
        }
    }

    /**
     * Clean up staging files when ViewModel is destroyed.
     * Handles cases where user navigates away without explicitly canceling or saving.
     */
    override fun onCleared() {
        super.onCleared()
        cleanUpStagedCover()
    }
}
