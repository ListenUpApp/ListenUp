package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.settle
import com.calypsan.listenup.client.domain.usecase.toNotice
import com.calypsan.listenup.client.domain.usecase.contributor.ContributorUpdateRequest
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
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
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

private val logger = KotlinLogging.logger {}

/**
 * Maximum number of merge-target candidates surfaced in the picker dialog.
 *
 * Public because the dialog has to tell the reader when the list it is showing is only the first
 * [MAX_MERGE_CANDIDATES] of a much longer one. A silently truncated list reads as a complete one,
 * which is how "my author isn't in here" became "merging is broken".
 */
const val MAX_MERGE_CANDIDATES = 30

/**
 * Lightweight projection of a contributor as a merge-target candidate.
 *
 * Used by [ContributorEditViewModel.mergeCandidates] to populate the contributor
 * merge picker dialog. [bookCount] is a placeholder (always `0`) — there is no
 * per-contributor book-count query yet, so the dialog hides it.
 */
data class ContributorCandidate(
    val id: ContributorId,
    val displayName: String,
    val bookCount: Int,
)

/**
 * UI state for the contributor editing screen.
 *
 * A contributor that could not be loaded is [LoadFailed], never an empty form whose Save would
 * write blank fields; Save, merges, a staged photo and their failures all live inside [Editing], so
 * a failure is shown beside the reader's unsaved edits rather than in place of them.
 */
sealed interface ContributorEditUiState {
    /** Reading the contributor. */
    data object Loading : ContributorEditUiState

    /** The contributor could not be loaded (it no longer exists). The screen shows [error] with Back. */
    data class LoadFailed(
        val contributorId: String,
        val error: AppError,
    ) : ContributorEditUiState

    /**
     * Editing [form]. [original] is the form as loaded, the baseline [hasChanges] diffs against.
     * [phase] says what is in flight; Save, a merge and a new photo each start only from
     * [EditPhase.Idle]. [inlineError] is a Save, merge or photo failure, shown with the form intact.
     */
    data class Editing(
        val form: ContributorEditForm,
        val original: ContributorEditForm,
        val phase: EditPhase = EditPhase.Idle,
        val inlineError: AppError? = null,
    ) : ContributorEditUiState {
        /** Whether anything differs from what was loaded — a field, a picked photo, or a new frame. */
        val hasChanges: Boolean
            get() =
                form.name != original.name ||
                    form.description != original.description ||
                    form.website != original.website ||
                    form.birthDate != original.birthDate ||
                    form.deathDate != original.deathDate ||
                    form.pendingImageData != null ||
                    form.pendingImageFrame != null

        /** Whether Save is on its way. */
        val isSaving: Boolean get() = phase == EditPhase.Saving

        /** Whether a picked photo is being staged for preview. */
        val isUploadingImage: Boolean get() = phase == EditPhase.StagingImage

        /** Whether a merge is on its way. */
        val mergeInProgress: Boolean get() = phase == EditPhase.Merging
    }
}

/**
 * The contributor edit form: the fields the reader can change, the staged photo, and the merge
 * picker's state.
 */
data class ContributorEditForm(
    // Contributor identity
    val contributorId: String = "",
    val imagePath: String? = null,
    /** How the saved photo is framed; null: the centre crop. */
    val imageFrame: ImageFrame? = null,
    // Pending image upload (staged locally until Save)
    val pendingImageData: ByteArray? = null,
    val pendingImageFilename: String? = null,
    /** The frame staged for Save: for the picked photo when one is staged, else for the saved photo. */
    val pendingImageFrame: ImageFrame? = null,
    // Staging image path for preview (separate from the main image)
    val stagingImagePath: String? = null,
    // Editable fields
    val name: String = "",
    val description: String = "",
    val website: String = "",
    val birthDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    val deathDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    // Alias management (Room-observed; updated reactively by the firehose)
    val aliases: List<String> = emptyList(),
    // Merge-target picker visibility — candidates are computed only while true
    val mergeDialogVisible: Boolean = false,
    // Merge-target picker query (drives candidate filtering)
    val mergeQuery: String = "",
    // Set when Save finds an existing contributor whose name matches the just-typed
    // name under forgiving normalization. Non-null drives the rename-collision prompt;
    // the rename is held back (not saved) until the user picks merge or keep-separate.
    val renameCollisionCandidate: ContributorCandidate? = null,
    /** May merge, unmerge, and merge on a rename collision (Curate library). */
    val canCurateLibrary: Boolean = false,
) {
    /** [description] for the Swift Export boundary: a member named `description` collides with `NSObject.description` and is never exported. */
    val descriptionText: String get() = description

    /**
     * Returns the image path to display - staging if available, otherwise the original.
     */
    val displayImagePath: String?
        get() = stagingImagePath ?: imagePath
}

/**
 * Events from the contributor edit UI.
 *
 * Merge/unmerge are server-canonical operations (Books-C2): the VM dispatches
 * the RPC; the server emits sync events with authoritative state; the Room aliases
 * Flow re-emits without optimistic local writes.
 */
sealed interface ContributorEditUiEvent {
    // Field changes

    /** User edited the contributor's name. */
    data class NameChanged(
        val name: String,
    ) : ContributorEditUiEvent

    /** User edited the biography/description. */
    data class DescriptionChanged(
        val description: String,
    ) : ContributorEditUiEvent

    /** User edited the website URL. */
    data class WebsiteChanged(
        val website: String,
    ) : ContributorEditUiEvent

    /** User changed the birth date (ISO 8601 `YYYY-MM-DD`, empty string clears). */
    data class BirthDateChanged(
        val date: String,
    ) : ContributorEditUiEvent

    /** User changed the death date (ISO 8601 `YYYY-MM-DD`, empty string clears). */
    data class DeathDateChanged(
        val date: String,
    ) : ContributorEditUiEvent

    // Image upload

    /** User chose an image for the contributor, framed by [frame] (null when not framed); staged for preview until Save. */
    data class UploadImage(
        val imageData: ByteArray,
        val filename: String,
        val frame: ImageFrame?,
    ) : ContributorEditUiEvent

    /** User re-framed the photo: a staged pick when there is one, else the saved photo. Applied on Save. */
    data class StageFrame(
        val frame: ImageFrame,
    ) : ContributorEditUiEvent

    // Actions
    data object Save : ContributorEditUiEvent

    data object Cancel : ContributorEditUiEvent

    data object DismissError : ContributorEditUiEvent

    /** User opened the merge-target picker; candidate computation starts. */
    data object MergeDialogOpened : ContributorEditUiEvent

    /** User dismissed the merge-target picker; candidates stop computing and the query clears. */
    data object MergeDialogDismissed : ContributorEditUiEvent

    /**
     * User picked [targetId] in the alias dialog to fold INTO the contributor being edited: the
     * edited (viewed) contributor stays canonical and gains [targetId]'s name as an alias, while
     * [targetId] itself is soft-deleted.
     */
    data class MergeInto(
        val targetId: ContributorId,
    ) : ContributorEditUiEvent

    /** User chose to split [aliasName] back out into its own contributor. */
    data class UnmergeAlias(
        val aliasName: String,
    ) : ContributorEditUiEvent

    // Rename-collision prompt (surfaced from Save when the new name matches an existing
    // contributor under forgiving normalization; see [ContributorEditForm.renameCollisionCandidate])

    /**
     * User chose to merge into [ContributorEditForm.renameCollisionCandidate] instead of
     * completing the rename: the contributor being edited is folded into the existing one,
     * which survives under its own name.
     */
    data object ConfirmMergeOnRename : ContributorEditUiEvent

    /** User chose to keep both contributors separate; the rename proceeds as typed. */
    data object KeepSeparateOnRename : ContributorEditUiEvent

    /** User dismissed the rename-collision prompt without choosing — the rename is NOT saved. */
    data object DismissRenameCollision : ContributorEditUiEvent
}

/**
 * Navigation actions from contributor edit screen.
 */
sealed interface ContributorEditNavAction {
    data object NavigateBack : ContributorEditNavAction

    data object SaveSuccess : ContributorEditNavAction

    /**
     * A merge committed; land on [contributorId], the contributor that survived it.
     *
     * Distinct from [NavigateBack] because a merge can delete the contributor being *viewed*: the
     * rename-collision path folds the viewed contributor into an existing one, so popping the
     * editor would drop the reader onto the detail page of a contributor that no longer exists —
     * an empty shell requiring a second Back to escape. The alias path keeps the viewed
     * contributor as the survivor, so landing on it is a refresh rather than a move; carrying the
     * survivor's id in both cases lets every host normalize the stack the same way.
     */
    data class NavigateToMerged(
        val contributorId: ContributorId,
    ) : ContributorEditNavAction
}

/**
 * ViewModel for the contributor edit screen.
 *
 * Handles:
 * - Loading contributor data for editing
 * - Image upload via [ImageRepository]
 * - Saving metadata changes via [UpdateContributorUseCase] (RPC-backed)
 * - Server-canonical merge/unmerge via [ContributorEditRepository]
 * - Rename-collision prompt: Save holds back a rename that collides (under
 *   [normalizeContributorName]) with another live contributor, offering merge or keep-separate
 * - Reactive alias display via [ContributorAliasDao] Room observation
 * - Tracking unsaved changes
 *
 * @property contributorRepository Repository for contributor data
 * @property updateContributorUseCase Use case for updating contributor metadata
 * @property imageRepository Repository for persistent image operations (upload, local save)
 * @property imageStagingRepository Repository for staging image operations (preview before save)
 * @property contributorEditRepository RPC dispatcher for merge/unmerge
 * @property contributorAliasDao DAO for observing the contributor's aliases (Room is read truth)
 * @property contributorDao DAO for browsing all contributors as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 * @param permissionsRepository Whether the signed-in user may merge and unmerge (Curate library)
 */
class ContributorEditViewModel internal constructor(
    private val contributorRepository: ContributorRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val pendingImageUploads: PendingImageUploads,
    private val contributorEditRepository: ContributorEditRepository,
    private val contributorAliasDao: ContributorAliasDao,
    private val contributorDao: ContributorDao,
    private val errorBus: ErrorBus,
    permissionsRepository: PermissionsRepository,
) : ViewModel() {
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

    // ========== Image Staging ==========

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
            // Save to staging location for preview (doesn't upload or overwrite the original)
            when (val saveResult = imageStagingRepository.saveContributorImageStaging(contributorId, imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getContributorImageStagingPath(contributorId)
                    logger.info { "Contributor image saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save is clicked
                    updateEditing { editState ->
                        editState.copy(
                            phase = EditPhase.Idle,
                            form =
                                editState.form.copy(
                                    stagingImagePath = stagingPath,
                                    pendingImageData = imageData,
                                    pendingImageFilename = filename,
                                    // A new pick replaces any staged frame.
                                    pendingImageFrame = frame,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save contributor image to staging: ${saveResult.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = saveResult.error) }
                }
            }
        }
    }

    /**
     * Commit the staged image to the durable local location and upload it to the server.
     * The local save is prioritized so the picked photo survives offline; a failed upload does not
     * fail the save but is queued to retry when it can succeed later, and reported as
     * [SaveOutcome.SavedImageNotUploaded]. Called from
     * [persistChanges] once metadata persists.
     */
    private suspend fun commitAndUploadImageIfPending(current: ContributorEditForm): SaveOutcome {
        val pendingData = current.pendingImageData ?: return SaveOutcome.Saved
        val pendingFilename = current.pendingImageFilename ?: return SaveOutcome.Saved
        val contributorId = current.contributorId

        // Room first: the new photo's frame (or its absence) is recorded for these exact bytes before any upload, so it
        // shows at once and a retried upload carries it (PendingImageUploads attaches it by hash).
        when (
            val stamped =
                contributorEditRepository.recordNewPhotoFrame(
                    ContributorId(contributorId),
                    pendingData,
                    current.pendingImageFrame,
                )
        ) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> logger.error { "Failed to record the new photo's frame: ${stamped.message}" }
        }

        // Then commit staging to the main image location (offline-first).
        when (val commitResult = imageStagingRepository.commitContributorImageStaging(contributorId)) {
            is AppResult.Success -> logger.info { "Staging image committed to main location" }
            is AppResult.Failure -> logger.error { "Failed to commit staging image: ${commitResult.message}" }
        }

        // Then upload to the server; the local image is already saved either way.
        val result =
            imageRepository.uploadContributorImage(
                contributorId = contributorId,
                imageData = pendingData,
                filename = pendingFilename,
                frame = current.pendingImageFrame,
            )
        when (result) {
            is AppResult.Success -> logger.info { "Contributor image uploaded to server" }
            is AppResult.Failure -> logger.error { "Failed to upload contributor image: ${result.message}" }
        }
        return pendingImageUploads.settle(PendingImageKind.ContributorImage, contributorId, result)
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
                (commitAndUploadImageIfPending(current) as? SaveOutcome.SavedImageNotUploaded)
                    ?.let { errorBus.emit(it.toNotice()) }
                // A re-frame of the saved photo (no new photo staged) is its own offline-first edit.
                val reframe = current.pendingImageFrame
                if (current.pendingImageData == null && reframe != null) {
                    val reframed =
                        contributorEditRepository.setImageFrame(
                            ContributorId(current.contributorId),
                            reframe,
                        )
                    if (reframed is AppResult.Failure) errorBus.emit(reframed.error)
                }
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
            imageStagingRepository.requestContributorImageStagingCleanup(current.contributorId)
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
