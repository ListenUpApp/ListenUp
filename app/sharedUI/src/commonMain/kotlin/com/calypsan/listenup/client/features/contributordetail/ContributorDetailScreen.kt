package com.calypsan.listenup.client.features.contributordetail

import com.calypsan.listenup.client.design.icons.ListenUpIcons
import androidx.compose.material3.LocalContentColor
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.design.components.CoverScrim
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.edit_frame_saved
import com.calypsan.listenup.client.design.components.rememberContributorImageRequest
import com.calypsan.listenup.client.design.image.FrameConfirm
import com.calypsan.listenup.client.design.image.FrameEditorDialog
import com.calypsan.listenup.client.design.image.FrameEditorSource
import com.calypsan.listenup.client.design.image.scallopFramePreviews
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.features.match.MatchReceiptHost
import com.calypsan.listenup.client.features.match.MatchReceiptSubject
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.contributor_from_your_library_this_action
import listenup.composeapp.generated.resources.contributor_no_longer_here
import listenup.composeapp.generated.resources.contributor_no_longer_here_detail
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.design.components.DetailUnavailable

/**
 * Artist Portfolio screen - an immersive contributor detail experience.
 *
 * Adapts layout based on screen width:
 * - Narrow: immersive vertical layout with dramatic hero and horizontal carousels
 * - Wide: compact header row (avatar beside info) + book grids per role section
 */
@Composable
fun ContributorDetailScreen(
    contributorId: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onViewAllClick: (contributorId: String, role: String) -> Unit,
    onMatchDetailsClick: (String) -> Unit,
    /**
     * The receipt after a person's Match details applied, given the person's name; a slot so a test can render
     * the screen without its ViewModel.
     */
    matchReceipt: @Composable (name: String, modifier: Modifier) -> Unit = { name, modifier ->
        MatchReceiptHost(subjectId = contributorId, subject = MatchReceiptSubject.Person(name), modifier = modifier)
    },
    viewModel: ContributorDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(contributorId) {
        viewModel.loadContributor(contributorId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var adjusting by rememberSaveable { mutableStateOf(false) }

    // Navigate back when a delete succeeds.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                ContributorDetailNavAction.Deleted -> onBackClick()
            }
        }
    }

    // Surface delete errors via snackbar.
    DeleteErrorSnackbar(
        deleteError = (state as? ContributorDetailUiState.Ready)?.deleteError,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::dismissDeleteError,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            ContributorDetailUiState.Idle, ContributorDetailUiState.Loading -> {
                ContributorDetailLoadingFrame(contributorId = contributorId, onBackClick = onBackClick)
            }

            // The contributor comes from Room, so there is no fetch to retry; Back is the way out.
            is ContributorDetailUiState.Error -> {
                DetailUnavailable(message = current.message, onBack = onBackClick, onRetry = null)
            }

            ContributorDetailUiState.NotFound -> {
                // Merged-away or genuinely missing contributor — a friendly terminal state with
                // its own back affordance, since the hero (which owns the back button) never loads.
                EmptyState(
                    title = stringResource(Res.string.contributor_no_longer_here),
                    subtitle = stringResource(Res.string.contributor_no_longer_here_detail),
                    icon = ListenUpIcons.PersonOffFilled,
                )
                HeroNavRow(
                    onBack = onBackClick,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }

            is ContributorDetailUiState.Ready -> {
                val adjustPhoto: (() -> Unit)? = current.contributor.imagePath?.let { { adjusting = true } }
                val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
                val useWideLayout =
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                    )

                if (useWideLayout) {
                    WideContributorPortfolio(
                        contributorId = contributorId,
                        state = current,
                        onBackClick = onBackClick,
                        onEditClick = { onEditClick(contributorId) },
                        onMatchDetails = { onMatchDetailsClick(contributorId) },
                        onAdjustPhoto = adjustPhoto,
                        onDeleteClick = { showDeleteConfirmation = true },
                        onBookClick = onBookClick,
                        onViewAllClick = { role -> onViewAllClick(contributorId, role) },
                    )
                } else {
                    NarrowContributorPortfolio(
                        contributorId = contributorId,
                        state = current,
                        onBackClick = onBackClick,
                        onEditClick = { onEditClick(contributorId) },
                        onMatchDetails = { onMatchDetailsClick(contributorId) },
                        onAdjustPhoto = adjustPhoto,
                        onDeleteClick = { showDeleteConfirmation = true },
                        onBookClick = onBookClick,
                        onViewAllClick = { role -> onViewAllClick(contributorId, role) },
                    )
                }

                if (showDeleteConfirmation) {
                    ListenUpDestructiveDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        title = stringResource(Res.string.common_delete_name, "Contributor"),
                        text =
                            "This will remove ${current.contributor.name} " +
                                stringResource(Res.string.contributor_from_your_library_this_action),
                        confirmText = stringResource(Res.string.common_delete),
                        onConfirm = {
                            showDeleteConfirmation = false
                            viewModel.confirmDelete()
                        },
                    )
                }

                if (adjusting) {
                    AdjustPhotoFromMenu(
                        contributor = current.contributor,
                        adjustPhoto = viewModel::adjustPhoto,
                        snackbarHostState = snackbarHostState,
                        snackbarScope = screenScope,
                        onClose = { adjusting = false },
                        onOpenEdit = { onEditClick(contributorId) },
                    )
                }

                if (current.isDeleting) {
                    CoverScrim(modifier = Modifier.fillMaxSize()) {
                        ListenUpLoadingIndicator(color = LocalContentColor.current)
                    }
                }

                // The receipt after Match details applied: "Changed photo and biography for Ray Porter" with Undo.
                matchReceipt(
                    current.contributor.name,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(LocalNowPlayingInsets.current.union(WindowInsets.navigationBars)),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Shows a failed delete in the snackbar, in words a person can act on, then reports it shown. */
@Composable
private fun DeleteErrorSnackbar(
    deleteError: String?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    LaunchedEffect(deleteError) {
        if (deleteError != null) {
            val friendlyMessage =
                when {
                    deleteError.contains("not found", ignoreCase = true) -> {
                        "Could not find metadata for this contributor on Audible"
                    }

                    deleteError.contains("network", ignoreCase = true) ||
                        deleteError.contains("connection", ignoreCase = true) -> {
                        "Network error. Please check your connection and try again."
                    }

                    else -> {
                        deleteError
                    }
                }
            snackbarHostState.showSnackbar(friendlyMessage)
            onShown()
        }
    }
}

/**
 * The overflow menu's Adjust photo, wired to the screen: Save commits through [viewModel] and says so in
 * the snackbar (the write lands in Room before any network, so it is done now); "Choose another" closes
 * the editor and goes to Contributor edit (R6).
 */
@Composable
internal fun AdjustPhotoFromMenu(
    contributor: Contributor,
    adjustPhoto: (ImageFrame) -> Unit,
    snackbarHostState: SnackbarHostState,
    snackbarScope: CoroutineScope,
    onClose: () -> Unit,
    onOpenEdit: () -> Unit,
) {
    val photoAdjusted = stringResource(Res.string.edit_frame_saved)
    ContributorPhotoAdjuster(
        contributorId = contributor.id.value,
        imagePath = contributor.imagePath,
        frame = contributor.imageFrame,
        onSave = { saved ->
            onClose()
            adjustPhoto(saved)
            // The screen's scope, not this one: closing the editor ends this composable's scope, and the
            // message would be cancelled before it showed.
            snackbarScope.launch { snackbarHostState.showSnackbar(photoAdjusted) }
        },
        onDismiss = onClose,
        onOpenEdit = {
            onClose()
            onOpenEdit()
        },
    )
}

/**
 * Adjust photo from the overflow menu: the editor over the photo the screen shows, with the scallop
 * and list-circle previews, saving straight away (Q2). An existing photo that will not open has no
 * other photo to choose here, so "Choose another" goes to Contributor edit, where one is picked (R6).
 */
@Composable
internal fun ContributorPhotoAdjuster(
    contributorId: String,
    imagePath: String?,
    frame: ImageFrame?,
    onSave: (ImageFrame) -> Unit,
    onDismiss: () -> Unit,
    onOpenEdit: () -> Unit,
) {
    val request = rememberContributorImageRequest(contributorId, imagePath)
    FrameEditorDialog(
        source = request?.let(FrameEditorSource::Model) ?: FrameEditorSource.Resolving,
        initialFrame = frame,
        shape = FrameShape.Circle,
        confirm = FrameConfirm.Save,
        previews = scallopFramePreviews(),
        onConfirm = onSave,
        onCancel = onDismiss,
        onChooseAnother = onOpenEdit,
    )
}
