package com.calypsan.listenup.client.presentation.contributoredit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.contributor.ContributorUpdateRequest
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.client.domain.usecase.toNotice
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.client.presentation.edit.EditPhase
import com.calypsan.listenup.client.presentation.edit.FormSlot
import com.calypsan.listenup.core.ContributorId
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

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the contributor edit screen.
 *
 * Handles:
 * - Loading contributor data for editing
 * - Delegating image staging/upload/reframe/cleanup to [ContributorEditImageHandler]
 * - Saving metadata changes via [UpdateContributorUseCase] (RPC-backed)
 * - Server-canonical merge/unmerge via [ContributorEditRepository]
 * - Rename-collision prompt: Save holds back a rename that collides (under
 *   [normalizeContributorName]) with another live contributor, offering merge or keep-separate
 * - Reactive alias display via [ContributorAliasDao] Room observation
 * - Tracking unsaved changes
 *
 * @property contributorRepository Repository for contributor data
 * @property updateContributorUseCase Use case for updating contributor metadata
 * @property contributorEditRepository RPC dispatcher for merge/unmerge
 * @property contributorAliasDao DAO for observing the contributor's aliases (Room is read truth)
 * @property contributorDao DAO for browsing all contributors as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 * @param permissionsRepository Whether the signed-in user may merge and unmerge (Curate library)
 */
class ContributorEditViewModel internal constructor(
    private val contributorRepository: ContributorRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    imageRepository: ImageRepository,
    imageStagingRepository: ImageStagingRepository,
    pendingImageUploads: PendingImageUploads,
    private val contributorEditRepository: ContributorEditRepository,
    private val contributorAliasDao: ContributorAliasDao,
    private val contributorDao: ContributorDao,
    private val errorBus: ErrorBus,
    permissionsRepository: PermissionsRepository,
) : ViewModel() {
    private val imageHandler =
        ContributorEditImageHandler(
            imageRepository = imageRepository,
            imageStagingRepository = imageStagingRepository,
            pendingImageUploads = pendingImageUploads,
            contributorEditRepository = contributorEditRepository,
        )

    val state: StateFlow<ContributorEditUiState>
        field = MutableStateFlow<ContributorEditUiState>(ContributorEditUiState.Loading)

    /** The form inside [ContributorEditUiState.Editing]; edits outside it are dropped. */
    private val form: FormSlot<ContributorEditUiState, ContributorEditForm> =
        FormSlot(
            state = state,
            formOf = { (it as? ContributorEditUiState.Editing)?.form },
            withForm = { current, form ->
                if (current is ContributorEditUiState.Editing) current.copy(form = form) else current
            },
        )

    /** May merge and unmerge (Curate library); held live so a form built later starts with it. */
    private val canCurateLibrary: StateFlow<Boolean> =
        permissionsRepository
            .observeCan(Permission.CURATE_LIBRARY)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val navActionsChannel = Channel<ContributorEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<ContributorEditNavAction> = navActionsChannel.receiveAsFlow()

    /**
     * Candidates for the merge-target picker — all live contributors except the current
     * one, filtered by [ContributorEditForm.mergeQuery] (case-insensitive substring).
     * Capped at [MAX_MERGE_CANDIDATES] to keep the dialog snappy.
     *
     * Computed only while the picker is visible: hidden emits an empty list without ever
     * collecting the DAO, and visible re-filters only when the query or the contributor
     * table changes — never on unrelated state churn such as typing in the name field,
     * which used to re-scan the whole table on every keystroke.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mergeCandidates: StateFlow<List<ContributorCandidate>> =
        form.flow
            .map { it?.mergeDialogVisible == true }
            .distinctUntilChanged()
            .flatMapLatest { pickerVisible ->
                if (!pickerVisible) {
                    flowOf(emptyList())
                } else {
                    combine(
                        form.flow.map { it?.contributorId to it?.mergeQuery.orEmpty() }.distinctUntilChanged(),
                        contributorDao.observeAll(),
                    ) { (currentId, query), allContributors ->
                        allContributors
                            .asSequence()
                            .filter { it.deletedAt == null }
                            .filter { it.id.value != currentId }
                            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                            .sortedBy { it.name.lowercase() }
                            .take(MAX_MERGE_CANDIDATES)
                            .map { entity ->
                                ContributorCandidate(
                                    id = entity.id,
                                    displayName = entity.name,
                                    bookCount = 0,
                                )
                            }.toList()
                    }
                }
            }.stateIn(viewModelScope, WhileSubscribedForUi, emptyList())

    init {
        canCurateLibrary
            .onEach { can -> form.update { it.copy(canCurateLibrary = can) } }
            .launchIn(viewModelScope)
    }

    /** The id whose data the form currently holds or is fetching; a repeat load for it is a no-op. */
    private var loadedContributorId: String? = null
    private var loadJob: Job? = null
    private var aliasJob: Job? = null

    /**
     * Load contributor data for editing.
     */
    fun loadContributor(contributorId: String) {
        // The screen asks again whenever it is recreated (rotation, theme, fold). The form already
        // holds this contributor, possibly with unsaved edits, so there is nothing to fetch.
        if (loadedContributorId == contributorId) return
        loadedContributorId = contributorId

        loadJob?.cancel()
        aliasJob?.cancel()
        state.value = ContributorEditUiState.Loading
        loadJob =
            viewModelScope.launch {
                val contributor = contributorRepository.getById(contributorId)
                if (contributor == null) {
                    // Let the same id be asked for again: nothing was loaded.
                    loadedContributorId = null
                    state.value = ContributorEditUiState.LoadFailed(contributorId, ContributorError.NotFound())
                    return@launch
                }

                val loaded =
                    ContributorEditForm(
                        contributorId = contributorId,
                        imagePath = contributor.imagePath,
                        imageFrame = contributor.imageFrame,
                        name = contributor.name,
                        description = contributor.description.orEmpty(),
                        website = contributor.website.orEmpty(),
                        birthDate = contributor.birthDate.orEmpty(),
                        deathDate = contributor.deathDate.orEmpty(),
                        canCurateLibrary = canCurateLibrary.value,
                    )
                state.value = ContributorEditUiState.Editing(form = loaded, original = loaded)

                // Subscribe to Room aliases (single source of truth; updated by the firehose).
                aliasJob =
                    contributorAliasDao
                        .observeForContributor(contributorId)
                        .onEach { aliases -> form.update { it.copy(aliases = aliases) } }
                        .launchIn(viewModelScope)

                logger.debug { "Loaded contributor for editing: ${contributor.name}" }
            }
    }

    /**
     * Update the merge-target picker's search query. The [mergeCandidates] Flow
     * re-emits a filtered list whenever this changes.
     */
    fun onMergeQueryChange(query: String) {
        form.update { it.copy(mergeQuery = query) }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: ContributorEditUiEvent) {
        when (event) {
            is ContributorEditUiEvent.NameChanged -> {
                form.update { it.copy(name = event.name) }
            }

            is ContributorEditUiEvent.DescriptionChanged -> {
                form.update { it.copy(description = event.description) }
            }

            is ContributorEditUiEvent.WebsiteChanged -> {
                form.update { it.copy(website = event.website) }
            }

            is ContributorEditUiEvent.BirthDateChanged -> {
                form.update { it.copy(birthDate = event.date) }
            }

            is ContributorEditUiEvent.DeathDateChanged -> {
                form.update { it.copy(deathDate = event.date) }
            }

            is ContributorEditUiEvent.UploadImage -> {
                stageImage(event.imageData, event.filename, event.frame)
            }

            is ContributorEditUiEvent.StageFrame -> {
                form.update { it.copy(pendingImageFrame = event.frame) }
            }

            is ContributorEditUiEvent.Save -> {
                saveChanges()
            }

            is ContributorEditUiEvent.Cancel -> {
                cancelAndCleanup()
            }

            is ContributorEditUiEvent.DismissError -> {
                updateEditing { it.copy(inlineError = null) }
            }

            is ContributorEditUiEvent.MergeDialogOpened -> {
                form.update { it.copy(mergeDialogVisible = true) }
            }

            is ContributorEditUiEvent.MergeDialogDismissed -> {
                form.update { it.copy(mergeDialogVisible = false, mergeQuery = "") }
            }

            is ContributorEditUiEvent.MergeInto -> {
                mergeInto(event.targetId)
            }

            is ContributorEditUiEvent.UnmergeAlias -> {
                unmergeAlias(event.aliasName)
            }

            is ContributorEditUiEvent.ConfirmMergeOnRename -> {
                mergeOnRename()
            }

            is ContributorEditUiEvent.KeepSeparateOnRename -> {
                keepSeparateOnRename()
            }

            is ContributorEditUiEvent.DismissRenameCollision -> {
                form.update { it.copy(renameCollisionCandidate = null) }
            }
        }
    }

    // ========== Merge / Unmerge ==========

    /** The [ContributorEditUiState.Editing] the screen is on, if it is editing and nothing is in flight. */
    private fun idleEditing(): ContributorEditUiState.Editing? =
        (state.value as? ContributorEditUiState.Editing)?.takeIf { it.phase == EditPhase.Idle }

    private inline fun updateEditing(transform: (ContributorEditUiState.Editing) -> ContributorEditUiState.Editing) {
        state.update { if (it is ContributorEditUiState.Editing) transform(it) else it }
    }

    /**
     * Fold the [chosen] contributor into the one whose page we're on. The **viewed** contributor is
     * canonical: it is the merge *target* (it survives and gains the chosen contributor's name as an
     * alias), while [chosen] is the merge *source* (soft-deleted). This matches the user's mental
     * model — "on J.K. Rowling's page, add Robert Galbraith as an alias" keeps Rowling and folds
     * Galbraith in. Passing these in the other order would delete the page you're viewing.
     */
    private fun mergeInto(chosen: ContributorId) {
        val editing = idleEditing() ?: return
        // The UI hides the control without Curate library; this keeps a stale tap from reaching the server.
        if (!editing.form.canCurateLibrary) return
        val viewedId = ContributorId(editing.form.contributorId)
        // Clear the query along with the picker — a failed merge must not leave a stale pre-filtered
        // query behind the next open (matches the dismiss path).
        state.value =
            editing.copy(
                phase = EditPhase.Merging,
                inlineError = null,
                form = editing.form.copy(mergeDialogVisible = false, mergeQuery = ""),
            )

        viewModelScope.launch {
            when (val result = contributorEditRepository.mergeContributor(source = chosen, target = viewedId)) {
                is AppResult.Success -> {
                    updateEditing { it.copy(phase = EditPhase.Idle) }
                    // The survivor, not back: the viewed contributor survived this merge, but the
                    // detail page behind the editor was rendered from pre-merge state.
                    navActionsChannel.trySend(ContributorEditNavAction.NavigateToMerged(viewedId))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge contributor: ${result.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
                }
            }
        }
    }

    /**
     * Split [aliasName] back into its own contributor. The aliases Room Flow re-emits
     * without [aliasName] once the firehose event lands; we stay on this screen.
     */
    private fun unmergeAlias(aliasName: String) {
        val editing = idleEditing() ?: return
        if (!editing.form.canCurateLibrary) return
        val contributorId = ContributorId(editing.form.contributorId)

        viewModelScope.launch {
            when (val result = contributorEditRepository.unmergeContributor(contributorId, aliasName)) {
                is AppResult.Success -> {
                    logger.debug { "Unmerged alias '$aliasName'; new contributor id=${result.data}" }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to unmerge alias '$aliasName': ${result.message}" }
                    updateEditing { it.copy(inlineError = result.error) }
                }
            }
        }
    }

    // ========== Image Staging & Save ==========

    /**
     * Handle image selection.
     * Saves the image to a staging location for preview.
     * Does NOT upload to the server or overwrite the main image until saveChanges() is called.
     */
    private fun stageImage(
        imageData: ByteArray,
        filename: String,
        frame: ImageFrame?,
    ) {
        val editing = idleEditing() ?: return
        val contributorId = editing.form.contributorId
        state.value = editing.copy(phase = EditPhase.StagingImage, inlineError = null)

        viewModelScope.launch {
            when (val staged = imageHandler.stage(contributorId, imageData)) {
                is AppResult.Success -> {
                    // Store pending data for upload when Save is clicked
                    updateEditing { editState ->
                        editState.copy(
                            phase = EditPhase.Idle,
                            form =
                                editState.form.copy(
                                    stagingImagePath = staged.data,
                                    pendingImageData = imageData,
                                    pendingImageFilename = filename,
                                    // A new pick replaces any staged frame.
                                    pendingImageFrame = frame,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(staged.error)
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = staged.error) }
                }
            }
        }
    }

    /**
     * Save all changes. If the name changed and now collides (under [normalizeContributorName])
     * with another live contributor, the save is held back and [ContributorEditForm.renameCollisionCandidate]
     * is set instead — [persistChanges] runs only once the user picks merge or keep-separate.
     *
     * The form enters [EditPhase.Saving] before the collision lookup suspends, so a second tap in
     * that window is refused rather than starting a second save.
     */
    private fun saveChanges() {
        val editing = idleEditing() ?: return
        if (!editing.hasChanges) {
            navActionsChannel.trySend(ContributorEditNavAction.NavigateBack)
            return
        }
        state.value = editing.copy(phase = EditPhase.Saving, inlineError = null)

        viewModelScope.launch {
            val current = editing.form
            if (current.name != editing.original.name) {
                val collision = findRenameCollision(current.contributorId, current.name)
                if (collision != null) {
                    updateEditing {
                        it.copy(phase = EditPhase.Idle, form = it.form.copy(renameCollisionCandidate = collision))
                    }
                    return@launch
                }
            }
            persistChanges(current)
        }
    }

    /**
     * Looks for another live (non-deleted) contributor whose name normalizes equal to [newName]
     * — a punctuation/spacing variant collision (e.g. "George R.R. Martin" vs "George R. R.
     * Martin"). Reads the same Room-backed contributor list [mergeCandidates] draws from, but
     * unfiltered by the merge-picker's search query and count cap.
     */
    private suspend fun findRenameCollision(
        currentId: String,
        newName: String,
    ): ContributorCandidate? {
        val normalizedNew = normalizeContributorName(newName)
        return contributorDao
            .observeAll()
            .first()
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { it.id.value != currentId }
            .firstOrNull { normalizeContributorName(it.name) == normalizedNew }
            ?.let { entity -> ContributorCandidate(id = entity.id, displayName = entity.name, bookCount = 0) }
    }

    /**
     * Persists the metadata via the use case — the actual write, shared by the no-collision
     * Save path and the "keep separate" choice on the rename-collision prompt. Runs in
     * [EditPhase.Saving]; a failure returns the form to idle with the error inline.
     */
    private suspend fun persistChanges(current: ContributorEditForm) {
        val result =
            updateContributorUseCase(
                ContributorUpdateRequest(
                    contributorId = current.contributorId,
                    name = current.name,
                    biography = current.description,
                    website = current.website,
                    birthDate = current.birthDate,
                    deathDate = current.deathDate,
                ),
            )

        when (result) {
            is AppResult.Success -> {
                // Metadata persisted — commit + upload the staged image if the user picked one.
                (imageHandler.commitAndUploadIfPending(current) as? SaveOutcome.SavedImageNotUploaded)
                    ?.let { errorBus.emit(it.toNotice()) }
                // A re-frame of the saved photo (no new photo staged) is its own offline-first edit.
                val reframed = imageHandler.applyReframeIfPending(current)
                if (reframed is AppResult.Failure) errorBus.emit(reframed.error)
                updateEditing { editState ->
                    val saved =
                        editState.form.copy(
                            pendingImageData = null,
                            pendingImageFilename = null,
                            pendingImageFrame = null,
                            stagingImagePath = null,
                        )
                    editState.copy(phase = EditPhase.Idle, form = saved, original = saved)
                }
                navActionsChannel.trySend(ContributorEditNavAction.SaveSuccess)
            }

            is AppResult.Failure -> {
                errorBus.emit(result.error)
                logger.error { "Failed to save contributor: ${result.message}" }
                updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
            }
        }
    }

    /**
     * User chose to merge on the rename-collision prompt: the contributor being edited is
     * the merge *source* (folded in, soft-deleted, its typed rename discarded) and
     * [ContributorEditForm.renameCollisionCandidate] is the *target* (survives under its own
     * name — which is what collided with the typed rename in the first place). On success there
     * is nothing left to show on this page, so we land on the survivor.
     */
    private fun mergeOnRename() {
        val editing = idleEditing() ?: return
        if (!editing.form.canCurateLibrary) return
        val candidate = editing.form.renameCollisionCandidate ?: return
        val viewedId = ContributorId(editing.form.contributorId)
        state.value =
            editing.copy(
                phase = EditPhase.Merging,
                inlineError = null,
                form = editing.form.copy(renameCollisionCandidate = null),
            )

        viewModelScope.launch {
            when (val result = contributorEditRepository.mergeContributor(source = viewedId, target = candidate.id)) {
                is AppResult.Success -> {
                    updateEditing { it.copy(phase = EditPhase.Idle) }
                    // The survivor, not back: this merge soft-deleted the contributor we were editing.
                    navActionsChannel.trySend(ContributorEditNavAction.NavigateToMerged(candidate.id))
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge on rename: ${result.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
                }
            }
        }
    }

    /**
     * User chose to keep both contributors separate on the rename-collision prompt: clears the
     * prompt and proceeds with the rename exactly as if no collision had been found. Refused unless
     * the prompt is up and nothing is in flight, so a double tap saves once.
     */
    private fun keepSeparateOnRename() {
        val editing = idleEditing() ?: return
        if (editing.form.renameCollisionCandidate == null) return
        val form = editing.form.copy(renameCollisionCandidate = null)
        state.value = editing.copy(phase = EditPhase.Saving, inlineError = null, form = form)
        viewModelScope.launch { persistChanges(form) }
    }

    /**
     * Cancel editing and clean up any staged image file, then navigate back.
     */
    private fun cancelAndCleanup() {
        cleanUpStagedImage()
        navActionsChannel.trySend(ContributorEditNavAction.NavigateBack)
    }

    private fun cleanUpStagedImage() {
        val current = form.value ?: return
        if (current.contributorId.isNotBlank() && current.stagingImagePath != null) {
            imageHandler.cleanUp(current.contributorId)
        }
    }

    /**
     * Clean up staging files when the ViewModel is destroyed.
     * Handles cases where the user navigates away without explicitly canceling or saving.
     */
    override fun onCleared() {
        super.onCleared()
        cleanUpStagedImage()
    }
}
