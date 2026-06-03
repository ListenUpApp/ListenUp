package com.calypsan.listenup.client.features.seriesedit

import com.calypsan.listenup.client.design.util.PlatformBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CardDefaults
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CoverColors
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.rememberCoverColors
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import com.calypsan.listenup.client.features.seriesedit.components.SeriesMergeDialog
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel
import com.calypsan.listenup.client.util.rememberImagePicker
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.book_edit_change_cover
import listenup.composeapp.generated.resources.common_description
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.book_edit_keep_editing
import listenup.composeapp.generated.resources.book_edit_unsaved_changes
import listenup.composeapp.generated.resources.book_edit_you_have_unsaved_changes_are
import listenup.composeapp.generated.resources.series_enter_a_description_for_this
import listenup.composeapp.generated.resources.series_merge_into
import listenup.composeapp.generated.resources.series_no_cover
import listenup.composeapp.generated.resources.series_series_cover
import listenup.composeapp.generated.resources.series_series_name

/**
 * Series Edit Screen - simplified edit screen for series metadata and cover.
 *
 * Design follows ContributorEditScreen pattern with:
 * - Dynamic gradient backdrop derived from cover
 * - Identity header with cover and name field
 * - Cards for description and metadata
 * - Extended FAB for save action
 */
@Composable
fun SeriesEditScreen(
    seriesId: String,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit,
    viewModel: SeriesEditViewModel = koinViewModel { parametersOf(seriesId) },
) {
    LaunchedEffect(seriesId) {
        viewModel.loadSeries(seriesId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val mergeCandidates by viewModel.mergeCandidates.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { navAction ->
            when (navAction) {
                is SeriesEditNavAction.NavigateBack -> onSaveSuccess()
            }
        }
    }

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = state.hasChanges) {
        showUnsavedChangesDialog = true
    }

    // Generate color palette from cover
    val colorScheme =
        rememberCoverColors(
            imagePath = state.displayCoverPath,
            refreshKey = state.pendingCoverData,
        )
    val surfaceColor = MaterialTheme.colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (!state.isLoading) {
                SaveFab(
                    hasChanges = state.hasChanges,
                    isSaving = state.isSaving,
                    onSave = { viewModel.onEvent(SeriesEditUiEvent.SaveClicked) },
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
            SeriesBackdrop(
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
                        onDismiss = { viewModel.onEvent(SeriesEditUiEvent.ErrorDismissed) },
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                else -> {
                    SeriesEditContent(
                        state = state,
                        colorScheme = colorScheme,
                        onEvent = viewModel::onEvent,
                        onBackClick = {
                            if (state.hasChanges) {
                                showUnsavedChangesDialog = true
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
                    )

                    // Floating overflow menu (parallel to back button inside identity header)
                    OverflowMenu(
                        surfaceColor = surfaceColor,
                        onMergeClick = { showMergeDialog = true },
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(16.dp),
                    )
                }
            }
        }
    }

    if (showUnsavedChangesDialog) {
        UnsavedChangesDialog(
            onDiscard = {
                showUnsavedChangesDialog = false
                viewModel.onEvent(SeriesEditUiEvent.CancelClicked)
            },
            onKeepEditing = { showUnsavedChangesDialog = false },
        )
    }

    if (showMergeDialog) {
        SeriesMergeDialog(
            candidates = mergeCandidates,
            query = state.mergeQuery,
            onQueryChange = viewModel::onMergeQueryChange,
            onConfirm = { targetId ->
                showMergeDialog = false
                viewModel.onEvent(SeriesEditUiEvent.MergeInto(targetId))
            },
            onDismiss = { showMergeDialog = false },
        )
    }
}

// =============================================================================
// OVERFLOW MENU
// =============================================================================

@Composable
private fun OverflowMenu(
    surfaceColor: Color,
    onMergeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(Res.string.book_detail_more_options),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.series_merge_into)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallMerge, null) },
                onClick = {
                    expanded = false
                    onMergeClick()
                },
            )
        }
    }
}

// =============================================================================
// BACKDROP
// =============================================================================

@Composable
private fun SeriesBackdrop(
    colorScheme: CoverColors,
    surfaceColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    colorScheme.vibrant.copy(alpha = 0.3f),
                                    colorScheme.darkVibrant.copy(alpha = 0.2f),
                                    surfaceColor.copy(alpha = 0.0f),
                                ),
                            startY = 0f,
                            endY = 1200f,
                        ),
                ),
    )
}

// =============================================================================
// FLOATING ACTION BUTTON
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
            text = error ?: "Unknown error",
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.common_dismiss))
        }
    }
}

// =============================================================================
// MAIN CONTENT
// =============================================================================

@Composable
private fun SeriesEditContent(
    state: SeriesEditUiState,
    colorScheme: CoverColors,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Image picker for cover uploads
    val imagePicker =
        rememberImagePicker { result ->
            when (result) {
                is ImagePickerResult.Success -> {
                    onEvent(SeriesEditUiEvent.CoverSelected(result.data, result.filename))
                }

                is ImagePickerResult.Cancelled -> { /* User cancelled */ }

                is ImagePickerResult.Error -> { /* Error is handled via the ViewModel's error state */ }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        // Identity Header with cover and name
        SeriesIdentityHeader(
            coverPath = state.displayCoverPath,
            name = state.name,
            colorScheme = colorScheme,
            isUploadingCover = state.isUploadingCover,
            onNameChange = { onEvent(SeriesEditUiEvent.NameChanged(it)) },
            onCoverClick = { imagePicker.launch() },
            onBackClick = onBackClick,
        )

        // Cards section
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Description card
            SeriesStudioCard(title = stringResource(Res.string.common_description)) {
                ListenUpTextArea(
                    value = state.description,
                    onValueChange = { onEvent(SeriesEditUiEvent.DescriptionChanged(it)) },
                    label = "Description",
                    placeholder = stringResource(Res.string.series_enter_a_description_for_this),
                )
            }
        }

        // Bottom spacing for FAB
        Spacer(modifier = Modifier.height(88.dp))
    }
}

// =============================================================================
// IDENTITY HEADER
// =============================================================================

@Suppress("LongMethod")
@Composable
private fun SeriesIdentityHeader(
    coverPath: String?,
    name: String,
    colorScheme: CoverColors,
    isUploadingCover: Boolean,
    onNameChange: (String) -> Unit,
    onCoverClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
    ) {
        // Floating back button
        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Cover + Name row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Large editable cover (120dp) - tappable for upload
            ElevatedCard(
                onClick = onCoverClick,
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = colorScheme.muted.copy(alpha = 0.3f),
                    ),
                modifier = Modifier.size(120.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverPath != null) {
                        ListenUpAsyncImage(
                            path = coverPath,
                            contentDescription = stringResource(Res.string.series_series_cover),
                            contentScale = ContentScale.Crop,
                            refreshKey = isUploadingCover,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp)),
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.series_no_cover),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Loading overlay during upload
                    if (isUploadingCover) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ListenUpLoadingIndicatorSmall(color = Color.White)
                        }
                    } else {
                        // Edit indicator
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = stringResource(Res.string.book_edit_change_cover),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // Name field - Large editorial style
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle =
                    TextStyle(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                placeholder = {
                    Text(
                        stringResource(Res.string.series_series_name),
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = DisplayFontFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedContainerColor = surfaceColor.copy(alpha = 0.4f),
                        unfocusedContainerColor = surfaceColor.copy(alpha = 0.2f),
                    ),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// =============================================================================
// STUDIO CARD
// =============================================================================

@Composable
private fun SeriesStudioCard(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
