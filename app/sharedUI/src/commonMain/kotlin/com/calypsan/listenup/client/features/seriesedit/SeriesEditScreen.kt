package com.calypsan.listenup.client.features.seriesedit

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.design.components.rememberForwardingTextFieldState
import androidx.compose.material3.LocalContentColor
import com.calypsan.listenup.client.design.components.CoverScrim
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import listenup.composeapp.generated.resources.merge_history_section_title
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.features.merge.MergeHistoryList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.SaveAction
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.util.rememberUnsavedWorkGuard
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import com.calypsan.listenup.client.features.seriesedit.components.SeriesMergeDialog
import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditForm
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.client.features.edit.EditInlineError
import com.calypsan.listenup.client.features.edit.EditLoadFailed
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel
import com.calypsan.listenup.client.util.rememberImagePicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.book_edit_change_cover
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_description
import listenup.composeapp.generated.resources.series_edit_series
import listenup.composeapp.generated.resources.series_enter_a_description_for_this
import listenup.composeapp.generated.resources.series_merge_into
import listenup.composeapp.generated.resources.series_no_cover
import listenup.composeapp.generated.resources.series_series_cover
import listenup.composeapp.generated.resources.series_series_name
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.layout.fillMaxHeight
import com.calypsan.listenup.client.features.seriesedit.components.AddSubSeriesSheet
import com.calypsan.listenup.client.features.seriesedit.components.MoveIntoPicker
import com.calypsan.listenup.client.features.seriesedit.components.MoveIntoPickerSheet
import com.calypsan.listenup.client.features.seriesedit.components.NewParentDialog
import com.calypsan.listenup.client.features.seriesedit.components.PlaceInLibrary
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import listenup.composeapp.generated.resources.series_place_in_library
import org.koin.core.parameter.parametersOf
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk

/**
 * Series Edit Screen — edit series metadata and cover.
 *
 * Layout:
 * - Color-blocked [SeriesIdentityHeader] hero (primaryContainer): cover + editable name + overflow
 * - Description card
 * - Save as a top-bar action, disabled until something changes
 */
@Composable
fun SeriesEditScreen(
    seriesId: String,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit,
    /**
     * A merge committed; the surviving series' id. Separate from [onSaveSuccess] because this
     * series no longer exists — the host must land on the survivor rather than pop back onto a
     * deleted detail page.
     */
    onMergedInto: (String) -> Unit,
    viewModel: SeriesEditViewModel = koinViewModel { parametersOf(seriesId) },
) {
    LaunchedEffect(seriesId) {
        viewModel.loadSeries(seriesId)
    }

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val editing = uiState as? SeriesEditUiState.Editing
    val mergeCandidates by viewModel.mergeCandidates.collectAsStateWithLifecycle()
    val mergeHistory by viewModel.mergeHistory.collectAsStateWithLifecycle()
    val parentPickerRows by viewModel.parentPickerRows.collectAsStateWithLifecycle()
    val addSubSeries by viewModel.addSubSeries.collectAsStateWithLifecycle()

    val pickerAsPane =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { navAction ->
            when (navAction) {
                is SeriesEditNavAction.NavigateBack -> onSaveSuccess()
                is SeriesEditNavAction.NavigateToMerged -> onMergedInto(navAction.seriesId.value)
            }
        }
    }

    val requestLeave =
        rememberUnsavedWorkGuard(
            dirty = editing?.hasChanges == true,
            onLeave = onBackClick,
            // Discard leaves through the ViewModel so it can clean up what the draft staged.
            onDiscard = { viewModel.onEvent(SeriesEditUiEvent.CancelClicked) },
        )

    ListenUpScaffold(
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = uiState) {
                SeriesEditUiState.Loading -> {
                    ListenUpLoadingIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                is SeriesEditUiState.LoadFailed -> {
                    EditLoadFailed(
                        error = current.error,
                        onBack = onBackClick,
                        onRetry = null,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                is SeriesEditUiState.Editing -> {
                    SeriesEditContent(
                        studio = current,
                        mergeHistory = mergeHistory,
                        onEvent = viewModel::onEvent,
                        onAddSubSeries = { viewModel.onAddSubSeriesEvent(AddSubSeriesEvent.Opened) },
                        pickerPane =
                            if (pickerAsPane && current.form.parentPickerVisible) {
                                {
                                    MoveIntoPicker(
                                        state = current.form,
                                        rows = parentPickerRows,
                                        onEvent = viewModel::onEvent,
                                    )
                                }
                            } else {
                                null
                            },
                        // The VM owns the dialog flag so candidate computation can start
                        // and stop with it.
                        onMergeClick = { viewModel.onEvent(SeriesEditUiEvent.MergeDialogOpened) },
                        onBackClick = requestLeave,
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

    val state = editing?.form ?: return

    HierarchyOverlays(
        state = state,
        rows = parentPickerRows,
        addSubSeries = addSubSeries,
        pickerAsSheet = !pickerAsPane,
        onEvent = viewModel::onEvent,
        onAddSubSeriesEvent = viewModel::onAddSubSeriesEvent,
    )

    if (state.mergeDialogVisible) {
        SeriesMergeDialog(
            candidates = mergeCandidates,
            // The VM caps the list, so a full page is the signal that more exist behind a search.
            truncated = mergeCandidates.size >= MAX_MERGE_CANDIDATES,
            query = state.mergeQuery,
            bookCount = state.bookCount,
            onQueryChange = viewModel::onMergeQueryChange,
            // MergeInto closes the dialog VM-side; dismissal also clears the query.
            onConfirm = { targetId -> viewModel.onEvent(SeriesEditUiEvent.MergeInto(targetId)) },
            onDismiss = { viewModel.onEvent(SeriesEditUiEvent.MergeDialogDismissed) },
        )
    }
}

/** The hierarchy's sheets and dialogs: "Move into…" (when not a side pane), New parent, Add sub-series. */
@Composable
private fun HierarchyOverlays(
    state: SeriesEditForm,
    rows: List<ParentPickerRow>,
    addSubSeries: AddSubSeriesUiState,
    pickerAsSheet: Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit,
) {
    if (state.parentPickerVisible && pickerAsSheet) {
        MoveIntoPickerSheet(state = state, rows = rows, onEvent = onEvent)
    }
    NewParentDialog(state = state, onEvent = onEvent)
    AddSubSeriesSheet(state = addSubSeries, onEvent = onAddSubSeriesEvent)
}

// =============================================================================
// OVERFLOW MENU
// =============================================================================

@Composable
private fun SeriesOverflowMenu(
    onMergeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = {
                haptics.press()
                expanded = true
            },
        ) {
            Icon(
                imageVector = ListenUpIcons.MoreVert,
                contentDescription = stringResource(Res.string.book_detail_more_options),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.series_merge_into)) },
                leadingIcon = { Icon(ListenUpIcons.CallMerge, null) },
                onClick = {
                    expanded = false
                    onMergeClick()
                },
            )
        }
    }
}

// =============================================================================
// DIALOGS
// =============================================================================

// =============================================================================
// MAIN CONTENT
// =============================================================================

@Composable
private fun SeriesEditContent(
    studio: SeriesEditUiState.Editing,
    mergeHistory: MergeHistoryState,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeries: () -> Unit,
    onMergeClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The "Move into…" picker as a side pane on wide windows, while it is open; null otherwise. */
    pickerPane: (@Composable () -> Unit)? = null,
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

    Row(modifier = modifier.fillMaxSize()) {
        SeriesEditFormFields(
            studio = studio,
            mergeHistory = mergeHistory,
            onEvent = onEvent,
            onAddSubSeries = onAddSubSeries,
            onMergeClick = onMergeClick,
            onBackClick = onBackClick,
            onCoverClick = { imagePicker.launch() },
            modifier = Modifier.weight(1f),
        )
        if (pickerPane != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = ContentShapes.card,
                modifier =
                    Modifier
                        .weight(PICKER_PANE_WEIGHT)
                        .fillMaxHeight()
                        .padding(Spacing.lg)
                        .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Box(Modifier.padding(top = Spacing.lg)) { pickerPane() }
            }
        }
    }
}

private const val PICKER_PANE_WEIGHT = 0.7f

@Composable
private fun SeriesEditFormFields(
    studio: SeriesEditUiState.Editing,
    mergeHistory: MergeHistoryState,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeries: () -> Unit,
    onMergeClick: () -> Unit,
    onBackClick: () -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = studio.form
    Column(
        modifier =
            modifier
                .fillMaxSize()
                // Inset the scroll range by the keyboard so the focused field (e.g. the last one)
                // scrolls above the IME instead of hiding behind it. Matches EditProfileScreen.
                .imePadding()
                .verticalScroll(rememberScrollState()),
    ) {
        // Identity Header with cover and name
        SeriesIdentityHeader(
            coverPath = state.displayCoverPath,
            name = state.name,
            isUploadingCover = studio.isUploadingCover,
            onNameChange = { onEvent(SeriesEditUiEvent.NameChanged(it)) },
            onCoverClick = onCoverClick,
            onMergeClick = onMergeClick,
            canMerge = state.canCurateLibrary,
            onBackClick = onBackClick,
            saveAction = {
                SaveAction(
                    onClick = { onEvent(SeriesEditUiEvent.SaveClicked) },
                    enabled = studio.hasChanges,
                    isBusy = studio.isSaving || studio.mergeInProgress,
                )
            },
        )

        // A Save, merge or cover failure sits above the cards, the edits below it untouched.
        studio.inlineError?.let { error ->
            EditInlineError(
                error = error,
                onDismiss = { onEvent(SeriesEditUiEvent.ErrorDismissed) },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }

        // Cards section — side by side from medium width, like ContributorEdit's studio cards.
        val isMediumOrLarger =
            currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
            )
        if (isMediumOrLarger) {
            Row(
                modifier = Modifier.padding(Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    DescriptionCard(state = state, onEvent = onEvent)
                    PlaceInLibraryCard(state = state, onEvent = onEvent, onAddSubSeries = onAddSubSeries)
                }
                if (state.canCurateLibrary) {
                    MergeHistoryCard(mergeHistory = mergeHistory, onEvent = onEvent, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DescriptionCard(state = state, onEvent = onEvent)
                PlaceInLibraryCard(state = state, onEvent = onEvent, onAddSubSeries = onAddSubSeries)
                if (state.canCurateLibrary) MergeHistoryCard(mergeHistory = mergeHistory, onEvent = onEvent)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
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
    isUploadingCover: Boolean,
    onNameChange: (String) -> Unit,
    onCoverClick: () -> Unit,
    onMergeClick: () -> Unit,
    canMerge: Boolean,
    onBackClick: () -> Unit,
    saveAction: @Composable () -> Unit,
) {
    val haptics = LocalHaptics.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = ContentShapes.hero,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The primaryContainer Surface bleeds edge-to-edge behind the status bar; inset
                    // only the content so the back button clears the system clock and stays tappable.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
        ) {
            // Top row: back navigation + screen title + Save + overflow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        haptics.press()
                        onBackClick()
                    },
                ) {
                    Icon(
                        imageVector = ListenUpIcons.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
                Text(
                    text = stringResource(Res.string.series_edit_series),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                saveAction()
                // Merging is Curate library's, and it is the menu's only item.
                if (canMerge) SeriesOverflowMenu(onMergeClick = onMergeClick)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cover + Name row
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Large editable cover (120dp) - tappable for upload
                ElevatedCard(
                    onClick = onCoverClick,
                    shape = ContentShapes.card,
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
                    colors =
                        CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                        .clip(ContentShapes.card),
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
                            CoverScrim(modifier = Modifier.fillMaxSize()) {
                                ListenUpLoadingIndicatorSmall(color = LocalContentColor.current)
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
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = ListenUpIcons.CameraAlt,
                                    contentDescription = stringResource(Res.string.book_edit_change_cover),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }

                // Name field - Large editorial style
                val nameState = rememberForwardingTextFieldState(name, onEdited = onNameChange)
                ListenUpTextField(
                    state = nameState,
                    textStyle =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    placeholder = stringResource(Res.string.series_series_name),
                    placeholderStyle =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = HeroInk.muted(),
                        ),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            cursorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            focusedBorderColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unfocusedBorderColor = HeroInk.outline(),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        }
    }
}

// =============================================================================
// STUDIO CARDS
// =============================================================================

@Composable
private fun DescriptionCard(
    state: SeriesEditForm,
    onEvent: (SeriesEditUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SeriesStudioCard(title = stringResource(Res.string.common_description), modifier = modifier) {
        val description =
            rememberForwardingTextFieldState(state.description) { onEvent(SeriesEditUiEvent.DescriptionChanged(it)) }
        ListenUpTextArea(
            state = description,
            label = stringResource(Res.string.common_description),
            placeholder = stringResource(Res.string.series_enter_a_description_for_this),
        )
    }
}

/** Where the series sits: its parent, its sub-series, and adding more — applied at once, online only. */
@Composable
private fun PlaceInLibraryCard(
    state: SeriesEditForm,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeriesStudioCard(title = stringResource(Res.string.series_place_in_library), modifier = modifier) {
        PlaceInLibrary(state = state, onEvent = onEvent, onAddSubSeries = onAddSubSeries)
    }
}

/** The merges folded into this series, each undoable (#1061). */
@Composable
private fun MergeHistoryCard(
    mergeHistory: MergeHistoryState,
    onEvent: (SeriesEditUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SeriesStudioCard(title = stringResource(Res.string.merge_history_section_title), modifier = modifier) {
        MergeHistoryList(
            state = mergeHistory,
            onUndo = { onEvent(SeriesEditUiEvent.UndoMerge(it)) },
            onRetry = { onEvent(SeriesEditUiEvent.RetryMergeHistory) },
        )
    }
}

@Composable
private fun SeriesStudioCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ContentShapes.card,
        colors =
            CardDefaults.cardColors(
                // High, as the book editor's StudioCard: with the shadow gone, the lift is the container level.
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
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
