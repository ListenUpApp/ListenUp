package com.calypsan.listenup.client.features.bookedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import com.calypsan.listenup.client.features.bookedit.components.ClassificationSection
import com.calypsan.listenup.client.features.bookedit.components.IdentifiersSection
import com.calypsan.listenup.client.features.bookedit.components.IdentityHeader
import com.calypsan.listenup.client.features.bookedit.components.LibrarySection
import com.calypsan.listenup.client.features.bookedit.components.PublishingSection
import com.calypsan.listenup.client.features.bookedit.components.SeriesSection
import com.calypsan.listenup.client.features.bookedit.components.StudioCard
import com.calypsan.listenup.client.features.bookedit.components.TalentSection
import com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import com.calypsan.listenup.client.util.rememberImagePicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_classification
import listenup.composeapp.generated.resources.book_edit_enter_a_description
import listenup.composeapp.generated.resources.book_edit_identifiers
import listenup.composeapp.generated.resources.book_edit_keep_editing
import listenup.composeapp.generated.resources.book_edit_publishing
import listenup.composeapp.generated.resources.book_edit_talent
import listenup.composeapp.generated.resources.book_edit_unsaved_changes
import listenup.composeapp.generated.resources.book_edit_you_have_unsaved_changes_are
import listenup.composeapp.generated.resources.common_description
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.common_library
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.error_unknown
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Book editing screen following Material 3 Expressive Design.
 *
 * Layout:
 * - Color-blocked [IdentityHeader] hero (primaryContainer): cover + editable title/subtitle
 * - Sectioned cards for each editing group (responsive single/two-column)
 * - Extended FAB for Save action
 */
@Suppress("UnusedParameter", "LongMethod", "CognitiveComplexMethod")
@Composable
fun BookEditScreen(
    bookId: String,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit = {},
    viewModel: BookEditViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { navAction ->
            when (navAction) {
                is BookEditNavAction.NavigateBack -> onBackClick()
                is BookEditNavAction.ShowSaveSuccess -> onBackClick()
            }
        }
    }

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = state.hasChanges) {
        showUnsavedChangesDialog = true
    }

    ListenUpScaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (!state.isLoading) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    ListenUpButton(
                        text = if (state.isSaving) "Saving..." else "Save Changes",
                        onClick = { viewModel.onEvent(BookEditUiEvent.Save) },
                        enabled = state.hasChanges && !state.isSaving,
                        isLoading = state.isSaving,
                        leadingIcon = Icons.Default.Save,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
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
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.error ?: stringResource(Res.string.error_unknown),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = { viewModel.onEvent(BookEditUiEvent.DismissError) },
                        ) {
                            Text(stringResource(Res.string.common_dismiss))
                        }
                    }
                }

                else -> {
                    BookEditContent(
                        bookId = bookId,
                        state = state,
                        onEvent = viewModel::onEvent,
                        onBackClick = {
                            if (state.hasChanges) {
                                showUnsavedChangesDialog = true
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier.padding(paddingValues),
                    )
                }
            }
        }
    }

    if (showUnsavedChangesDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = stringResource(Res.string.book_edit_unsaved_changes),
            text = stringResource(Res.string.book_edit_you_have_unsaved_changes_are),
            confirmText = stringResource(Res.string.common_discard),
            onConfirm = {
                showUnsavedChangesDialog = false
                onBackClick()
            },
            dismissText = stringResource(Res.string.book_edit_keep_editing),
            onDismiss = { showUnsavedChangesDialog = false },
        )
    }
}

// =============================================================================
// CONTENT LAYOUT
// =============================================================================

@Suppress("UnusedParameter")
@Composable
private fun BookEditContent(
    bookId: String,
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Detect window size for responsive layout
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isMediumOrLarger =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    // Image picker for cover uploads
    val imagePicker =
        rememberImagePicker { result ->
            when (result) {
                is ImagePickerResult.Success -> {
                    onEvent(BookEditUiEvent.UploadCover(result.data, result.filename))
                }

                is ImagePickerResult.Cancelled -> { /* User cancelled */ }

                is ImagePickerResult.Error -> {
                    // Error is handled via the ViewModel's error state
                }
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
        // Identity Header with navigation
        IdentityHeader(
            coverPath = state.displayCoverPath,
            refreshKey = state.pendingCoverData,
            title = state.title,
            subtitle = state.subtitle,
            isUploadingCover = state.isUploadingCover,
            onTitleChange = { onEvent(BookEditUiEvent.TitleChanged(it)) },
            onSubtitleChange = { onEvent(BookEditUiEvent.SubtitleChanged(it)) },
            onCoverClick = { imagePicker.launch() },
            onBackClick = onBackClick,
            bookId = bookId,
            coverHash = state.coverHash,
        )

        // Cards section - responsive layout
        if (isMediumOrLarger) {
            TwoColumnCardsLayout(state = state, onEvent = onEvent)
        } else {
            SingleColumnCardsLayout(state = state, onEvent = onEvent)
        }
    }
}

/**
 * Single-column layout for mobile (Compact) screens.
 */
@Composable
private fun SingleColumnCardsLayout(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Card 1: Description (The Hook)
        StudioCard(title = stringResource(Res.string.common_description)) {
            ListenUpTextArea(
                value = state.description,
                onValueChange = { onEvent(BookEditUiEvent.DescriptionChanged(it)) },
                label = "Description",
                placeholder = stringResource(Res.string.book_edit_enter_a_description),
            )
        }

        // Card 2: Publishing
        StudioCard(title = stringResource(Res.string.book_edit_publishing)) {
            PublishingSection(
                publisher = state.publisher,
                publishYear = state.publishYear,
                language = state.language,
                onPublisherChange = { onEvent(BookEditUiEvent.PublisherChanged(it)) },
                onPublishYearChange = { onEvent(BookEditUiEvent.PublishYearChanged(it)) },
                onLanguageChange = { onEvent(BookEditUiEvent.LanguageChanged(it)) },
            )
        }

        // Card 3: Identifiers & Format
        StudioCard(title = stringResource(Res.string.book_edit_identifiers)) {
            IdentifiersSection(
                isbn = state.isbn,
                asin = state.asin,
                abridged = state.abridged,
                onIsbnChange = { onEvent(BookEditUiEvent.IsbnChanged(it)) },
                onAsinChange = { onEvent(BookEditUiEvent.AsinChanged(it)) },
                onAbridgedChange = { onEvent(BookEditUiEvent.AbridgedChanged(it)) },
            )
        }

        // Card 4: Library
        StudioCard(title = stringResource(Res.string.common_library)) {
            LibrarySection(
                sortTitle = state.sortTitle,
                addedAt = state.addedAt,
                onSortTitleChange = { onEvent(BookEditUiEvent.SortTitleChanged(it)) },
                onAddedAtChange = { onEvent(BookEditUiEvent.AddedAtChanged(it)) },
            )
        }

        // Card 5: Series
        StudioCard(title = stringResource(Res.string.common_series)) {
            SeriesSection(
                series = state.series,
                searchQuery = state.seriesSearchQuery,
                searchResults = state.seriesSearchResults,
                isLoading = state.seriesSearchLoading,
                isOffline = state.seriesOfflineResult,
                onSearchQueryChange = { onEvent(BookEditUiEvent.SeriesSearchQueryChanged(it)) },
                onSeriesSelected = { onEvent(BookEditUiEvent.SeriesSelected(it)) },
                onSeriesEntered = { onEvent(BookEditUiEvent.SeriesEntered(it)) },
                onSequenceChange = { series, seq -> onEvent(BookEditUiEvent.SeriesSequenceChanged(series, seq)) },
                onRemoveSeries = { onEvent(BookEditUiEvent.RemoveSeries(it)) },
            )
        }

        // Card 5: Classification
        ClassificationCard(state = state, onEvent = onEvent)

        // Card 6: Talent
        StudioCard(title = stringResource(Res.string.book_edit_talent)) {
            TalentSection(
                visibleRoles = state.visibleRoles,
                availableRolesToAdd = state.availableRolesToAdd,
                contributorsForRole = state::contributorsForRole,
                roleSearchQueries = state.roleSearchQueries,
                roleSearchResults = state.roleSearchResults,
                roleSearchLoading = state.roleSearchLoading,
                roleOfflineResults = state.roleOfflineResults,
                onRoleSearchQueryChange = {
                    role,
                    query,
                    ->
                    onEvent(BookEditUiEvent.RoleSearchQueryChanged(role, query))
                },
                onContributorSelected = {
                    role,
                    result,
                    ->
                    onEvent(BookEditUiEvent.RoleContributorSelected(role, result))
                },
                onContributorEntered = { role, name -> onEvent(BookEditUiEvent.RoleContributorEntered(role, name)) },
                onRemoveContributor = {
                    contributor,
                    role,
                    ->
                    onEvent(BookEditUiEvent.RemoveContributor(contributor, role))
                },
                onAddRoleSection = { onEvent(BookEditUiEvent.AddRoleSection(it)) },
                onRemoveRoleSection = { onEvent(BookEditUiEvent.RemoveRoleSection(it)) },
            )
        }
    }
}

/**
 * Classification card (genres, tags, moods, collections) — shared by the single-column and
 * two-column layouts so the lengthy [ClassificationSection] wiring lives in one place.
 */
@Composable
private fun ClassificationCard(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    StudioCard(title = stringResource(Res.string.book_edit_classification)) {
        ClassificationSection(
            genres = state.genres,
            genreSearchQuery = state.genreSearchQuery,
            genreSearchResults = state.genreSearchResults,
            tags = state.tags,
            tagSearchQuery = state.tagSearchQuery,
            tagSearchResults = state.tagSearchResults,
            isTagSearching = state.tagSearchLoading,
            isTagCreating = state.tagCreating,
            moods = state.moods,
            moodSearchQuery = state.moodSearchQuery,
            moodSearchResults = state.moodSearchResults,
            isMoodSearching = state.moodSearchLoading,
            isMoodCreating = state.moodCreating,
            isAdmin = state.isAdmin,
            collections = state.collections,
            collectionSearchQuery = state.collectionSearchQuery,
            collectionSearchResults = state.collectionSearchResults,
            onGenreSearchQueryChange = { onEvent(BookEditUiEvent.GenreSearchQueryChanged(it)) },
            onGenreSelected = { onEvent(BookEditUiEvent.GenreSelected(it)) },
            onRemoveGenre = { onEvent(BookEditUiEvent.RemoveGenre(it)) },
            onTagSearchQueryChange = { onEvent(BookEditUiEvent.TagSearchQueryChanged(it)) },
            onTagSelected = { onEvent(BookEditUiEvent.TagSelected(it)) },
            onTagEntered = { onEvent(BookEditUiEvent.TagEntered(it)) },
            onRemoveTag = { onEvent(BookEditUiEvent.RemoveTag(it)) },
            onMoodSearchQueryChange = { onEvent(BookEditUiEvent.MoodSearchQueryChanged(it)) },
            onMoodSelected = { onEvent(BookEditUiEvent.MoodSelected(it)) },
            onMoodEntered = { onEvent(BookEditUiEvent.MoodEntered(it)) },
            onRemoveMood = { onEvent(BookEditUiEvent.RemoveMood(it)) },
            onCollectionSearchQueryChange = { onEvent(BookEditUiEvent.CollectionSearchQueryChanged(it)) },
            onCollectionSelected = { onEvent(BookEditUiEvent.CollectionSelected(it)) },
            onRemoveCollection = { onEvent(BookEditUiEvent.RemoveCollection(it)) },
        )
    }
}

/**
 * Two-column layout for tablet (Medium/Expanded) screens.
 *
 * Full Width: Description (The Hook - primary content)
 * Left Column: Publishing, Identifiers, Series
 * Right Column: Classification, Talent
 */
@Suppress("LongMethod")
@Composable
private fun TwoColumnCardsLayout(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Full-width: Description (The Hook) - Primary content
        StudioCard(title = stringResource(Res.string.common_description)) {
            ListenUpTextArea(
                value = state.description,
                onValueChange = { onEvent(BookEditUiEvent.DescriptionChanged(it)) },
                label = "Description",
                placeholder = stringResource(Res.string.book_edit_enter_a_description),
            )
        }

        // Two-column grid for remaining cards
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Left Column - Book metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StudioCard(title = stringResource(Res.string.book_edit_publishing)) {
                    PublishingSection(
                        publisher = state.publisher,
                        publishYear = state.publishYear,
                        language = state.language,
                        onPublisherChange = { onEvent(BookEditUiEvent.PublisherChanged(it)) },
                        onPublishYearChange = { onEvent(BookEditUiEvent.PublishYearChanged(it)) },
                        onLanguageChange = { onEvent(BookEditUiEvent.LanguageChanged(it)) },
                    )
                }

                StudioCard(title = stringResource(Res.string.book_edit_identifiers)) {
                    IdentifiersSection(
                        isbn = state.isbn,
                        asin = state.asin,
                        abridged = state.abridged,
                        onIsbnChange = { onEvent(BookEditUiEvent.IsbnChanged(it)) },
                        onAsinChange = { onEvent(BookEditUiEvent.AsinChanged(it)) },
                        onAbridgedChange = { onEvent(BookEditUiEvent.AbridgedChanged(it)) },
                    )
                }

                StudioCard(title = stringResource(Res.string.common_library)) {
                    LibrarySection(
                        sortTitle = state.sortTitle,
                        addedAt = state.addedAt,
                        onSortTitleChange = { onEvent(BookEditUiEvent.SortTitleChanged(it)) },
                        onAddedAtChange = { onEvent(BookEditUiEvent.AddedAtChanged(it)) },
                    )
                }

                StudioCard(title = stringResource(Res.string.common_series)) {
                    SeriesSection(
                        series = state.series,
                        searchQuery = state.seriesSearchQuery,
                        searchResults = state.seriesSearchResults,
                        isLoading = state.seriesSearchLoading,
                        isOffline = state.seriesOfflineResult,
                        onSearchQueryChange = { onEvent(BookEditUiEvent.SeriesSearchQueryChanged(it)) },
                        onSeriesSelected = { onEvent(BookEditUiEvent.SeriesSelected(it)) },
                        onSeriesEntered = { onEvent(BookEditUiEvent.SeriesEntered(it)) },
                        onSequenceChange = {
                            series,
                            seq,
                            ->
                            onEvent(BookEditUiEvent.SeriesSequenceChanged(series, seq))
                        },
                        onRemoveSeries = { onEvent(BookEditUiEvent.RemoveSeries(it)) },
                    )
                }
            }

            // Right Column - Classification & Talent
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ClassificationCard(state = state, onEvent = onEvent)

                StudioCard(title = stringResource(Res.string.book_edit_talent)) {
                    TalentSection(
                        visibleRoles = state.visibleRoles,
                        availableRolesToAdd = state.availableRolesToAdd,
                        contributorsForRole = state::contributorsForRole,
                        roleSearchQueries = state.roleSearchQueries,
                        roleSearchResults = state.roleSearchResults,
                        roleSearchLoading = state.roleSearchLoading,
                        roleOfflineResults = state.roleOfflineResults,
                        onRoleSearchQueryChange = {
                            role,
                            query,
                            ->
                            onEvent(BookEditUiEvent.RoleSearchQueryChanged(role, query))
                        },
                        onContributorSelected = {
                            role,
                            result,
                            ->
                            onEvent(BookEditUiEvent.RoleContributorSelected(role, result))
                        },
                        onContributorEntered = {
                            role,
                            name,
                            ->
                            onEvent(BookEditUiEvent.RoleContributorEntered(role, name))
                        },
                        onRemoveContributor = {
                            contributor,
                            role,
                            ->
                            onEvent(BookEditUiEvent.RemoveContributor(contributor, role))
                        },
                        onAddRoleSection = { onEvent(BookEditUiEvent.AddRoleSection(it)) },
                        onRemoveRoleSection = { onEvent(BookEditUiEvent.RemoveRoleSection(it)) },
                    )
                }
            }
        }
    }
}
