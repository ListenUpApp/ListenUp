package com.calypsan.listenup.client.features.contributoredit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpDatePicker
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import com.calypsan.listenup.client.features.contributoredit.components.AliasesSection
import com.calypsan.listenup.client.features.contributoredit.components.ContributorBackdrop
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.ContributorIdentityHeader
import com.calypsan.listenup.client.features.contributoredit.components.ContributorMergeDialog
import com.calypsan.listenup.client.features.contributoredit.components.ContributorStudioCard
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel
import com.calypsan.listenup.client.util.rememberImagePicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_keep_editing
import listenup.composeapp.generated.resources.book_edit_unsaved_changes
import listenup.composeapp.generated.resources.book_edit_you_have_unsaved_changes_are
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.contributor_biography
import listenup.composeapp.generated.resources.contributor_birth_date
import listenup.composeapp.generated.resources.contributor_dates
import listenup.composeapp.generated.resources.contributor_death_date
import listenup.composeapp.generated.resources.contributor_enter_a_biography
import listenup.composeapp.generated.resources.contributor_links
import listenup.composeapp.generated.resources.contributor_select_birth_date
import listenup.composeapp.generated.resources.contributor_select_death_date
import listenup.composeapp.generated.resources.error_unknown
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Artist Studio - immersive contributor editing experience.
 *
 * Design Philosophy: "The person is the brand."
 * Uses dynamic color gradients derived from contributor ID to create
 * a personalized editing studio that feels like managing an artist's profile.
 *
 * Layout Hierarchy:
 * 1. Dynamic Gradient Backdrop - Rich colors from avatar palette
 * 2. Identity Header - Large avatar + Name field side by side
 * 3. Content Cards - Biography, Links, Dates, Aliases
 * 4. Extended FAB - Save action always visible
 *
 * Responsive Design:
 * - Mobile: Single column card layout
 * - Tablet: Two-column layout with Biography spanning full width
 */
@Composable
fun ContributorEditScreen(
    contributorId: String,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit = {},
    viewModel: ContributorEditViewModel = koinViewModel(),
) {
    LaunchedEffect(contributorId) {
        viewModel.loadContributor(contributorId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val mergeCandidates by viewModel.mergeCandidates.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { navAction ->
            when (navAction) {
                is ContributorEditNavAction.NavigateBack -> onBackClick()
                is ContributorEditNavAction.SaveSuccess -> onSaveSuccess()
            }
        }
    }

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = state.hasChanges) {
        showUnsavedChangesDialog = true
    }

    // Generate color palette from contributor ID
    val colorScheme = rememberContributorColorScheme(contributorId)
    val surfaceColor = MaterialTheme.colorScheme.surface

    ListenUpScaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (!state.isLoading) {
                SaveFab(
                    hasChanges = state.hasChanges,
                    isSaving = state.isSaving,
                    onSave = { viewModel.onEvent(ContributorEditUiEvent.Save) },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(surfaceColor),
        ) {
            // Immersive gradient backdrop
            ContributorBackdrop(
                colorScheme = colorScheme,
                surfaceColor = surfaceColor,
            )

            // Content
            when {
                state.isLoading -> {
                    ListenUpLoadingIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                state.error != null -> {
                    ErrorContent(
                        error = state.error,
                        onDismiss = { viewModel.onEvent(ContributorEditUiEvent.DismissError) },
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                else -> {
                    ArtistStudioContent(
                        state = state,
                        colorScheme = colorScheme,
                        onEvent = viewModel::onEvent,
                        onMergeClick = { showMergeDialog = true },
                        onBackClick = {
                            if (state.hasChanges) {
                                showUnsavedChangesDialog = true
                            } else {
                                onBackClick()
                            }
                        },
                        // consumeWindowInsets so the inner Column's imePadding() doesn't double-count the
                        // Scaffold's already-reserved bottom inset — otherwise a surface-colored band
                        // appears between the field and the keyboard.
                        modifier =
                            Modifier
                                .padding(bottom = paddingValues.calculateBottomPadding())
                                .consumeWindowInsets(PaddingValues(bottom = paddingValues.calculateBottomPadding())),
                    )
                }
            }
        }
    }

    if (showUnsavedChangesDialog) {
        UnsavedChangesDialog(
            onDiscard = {
                showUnsavedChangesDialog = false
                onBackClick()
            },
            onKeepEditing = { showUnsavedChangesDialog = false },
        )
    }

    if (showMergeDialog) {
        ContributorMergeDialog(
            candidates = mergeCandidates,
            query = state.mergeQuery,
            onQueryChange = viewModel::onMergeQueryChange,
            onConfirm = { targetId ->
                showMergeDialog = false
                viewModel.onEvent(ContributorEditUiEvent.MergeInto(targetId))
            },
            onDismiss = {
                showMergeDialog = false
                viewModel.onMergeQueryChange("")
            },
        )
    }
}

// =============================================================================
// EXTENDED FAB
// =============================================================================

@Composable
private fun SaveFab(
    hasChanges: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    ListenUpExtendedFab(
        onClick = onSave,
        icon = Icons.Default.Save,
        text = if (isSaving) "Saving..." else "Save Changes",
        enabled = hasChanges && !isSaving,
        isLoading = isSaving,
    )
}

// =============================================================================
// DIALOGS
// =============================================================================

@Composable
private fun UnsavedChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(Res.string.book_edit_unsaved_changes),
        text = stringResource(Res.string.book_edit_you_have_unsaved_changes_are),
        confirmText = stringResource(Res.string.common_discard),
        onConfirm = onDiscard,
        dismissText = stringResource(Res.string.book_edit_keep_editing),
        onDismiss = onKeepEditing,
    )
}

@Composable
private fun ErrorContent(
    error: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error ?: stringResource(Res.string.error_unknown),
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.common_dismiss))
        }
    }
}

// =============================================================================
// ARTIST STUDIO CONTENT
// =============================================================================

@Composable
private fun ArtistStudioContent(
    state: ContributorEditUiState,
    colorScheme: ContributorColorScheme,
    onEvent: (ContributorEditUiEvent) -> Unit,
    onMergeClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isMediumOrLarger = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val imagePicker =
        rememberImagePicker { result ->
            when (result) {
                is ImagePickerResult.Success -> {
                    onEvent(ContributorEditUiEvent.UploadImage(result.data, result.filename))
                }

                is ImagePickerResult.Error -> {
                    // Error is logged by the image picker; user will see nothing happened
                }

                ImagePickerResult.Cancelled -> { /* User cancelled */ }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                // Inset the scroll range by the keyboard so the focused field (e.g. the last one)
                // scrolls above the IME instead of hiding behind it. Matches EditProfileScreen.
                .imePadding()
                .verticalScroll(rememberScrollState()),
    ) {
        // Identity Header with avatar and name
        ContributorIdentityHeader(
            contributorId = state.contributorId,
            imagePath = state.imagePath,
            name = state.name,
            colorScheme = colorScheme,
            isUploadingImage = state.isUploadingImage,
            onNameChange = { onEvent(ContributorEditUiEvent.NameChanged(it)) },
            onAvatarClick = { imagePicker.launch() },
            onBackClick = onBackClick,
        )

        // Cards section - responsive layout
        if (isMediumOrLarger) {
            TwoColumnCardsLayout(state = state, onEvent = onEvent, onMergeClick = onMergeClick)
        } else {
            SingleColumnCardsLayout(state = state, onEvent = onEvent, onMergeClick = onMergeClick)
        }
    }
}

// =============================================================================
// SINGLE COLUMN LAYOUT (Mobile)
// =============================================================================

@Composable
private fun SingleColumnCardsLayout(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
    onMergeClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ContributorStudioCard(title = stringResource(Res.string.contributor_biography)) {
            BiographyCardContent(state = state, onEvent = onEvent)
        }

        ContributorStudioCard(title = stringResource(Res.string.contributor_links)) {
            LinksCardContent(state = state, onEvent = onEvent)
        }

        ContributorStudioCard(title = stringResource(Res.string.contributor_dates)) {
            DatesCardContent(state = state, onEvent = onEvent)
        }

        // Aliases (always visible; shows a hint + merge button when empty)
        AliasesSection(
            aliases = state.aliases,
            onUnmerge = { aliasName ->
                onEvent(ContributorEditUiEvent.UnmergeAlias(aliasName))
            },
            onMergeClick = onMergeClick,
        )
    }
}

// =============================================================================
// TWO COLUMN LAYOUT (Tablet)
// =============================================================================

@Composable
private fun TwoColumnCardsLayout(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
    onMergeClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Full-width: Biography (primary content)
        ContributorStudioCard(title = stringResource(Res.string.contributor_biography)) {
            BiographyCardContent(state = state, onEvent = onEvent)
        }

        // Two-column grid: Links + Dates
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ContributorStudioCard(title = stringResource(Res.string.contributor_links)) {
                    LinksCardContent(state = state, onEvent = onEvent)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ContributorStudioCard(title = stringResource(Res.string.contributor_dates)) {
                    DatesCardContent(state = state, onEvent = onEvent)
                }
            }
        }

        // Aliases (always visible; shows a hint + merge button when empty)
        AliasesSection(
            aliases = state.aliases,
            onUnmerge = { aliasName ->
                onEvent(ContributorEditUiEvent.UnmergeAlias(aliasName))
            },
            onMergeClick = onMergeClick,
        )
    }
}

// =============================================================================
// CARD CONTENTS
// =============================================================================

@Composable
private fun BiographyCardContent(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    ListenUpTextArea(
        value = state.description,
        onValueChange = { onEvent(ContributorEditUiEvent.DescriptionChanged(it)) },
        label = "Bio",
        placeholder = stringResource(Res.string.contributor_enter_a_biography),
    )
}

@Composable
private fun LinksCardContent(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    ListenUpTextField(
        value = state.website,
        onValueChange = { onEvent(ContributorEditUiEvent.WebsiteChanged(it)) },
        label = "Website",
        placeholder = "https://...",
        // A URL keyboard alone still autocorrects words and injects spaces; disable text
        // assistance so URLs are typed verbatim.
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
    )
}

@Composable
private fun DatesCardContent(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListenUpDatePicker(
            value = state.birthDate,
            onValueChange = { onEvent(ContributorEditUiEvent.BirthDateChanged(it)) },
            label = stringResource(Res.string.contributor_birth_date),
            placeholder = stringResource(Res.string.contributor_select_birth_date),
        )

        ListenUpDatePicker(
            value = state.deathDate,
            onValueChange = { onEvent(ContributorEditUiEvent.DeathDateChanged(it)) },
            label = stringResource(Res.string.contributor_death_date),
            placeholder = stringResource(Res.string.contributor_select_death_date),
        )
    }
}
