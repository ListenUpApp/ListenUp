package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.presentation.edit.EditPhase
import com.calypsan.listenup.core.ContributorId

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
 * Merge/unmerge are server-canonical operations: the VM dispatches the RPC, the server emits
 * sync events with authoritative state, and the Room aliases Flow re-emits without optimistic local writes.
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
