package com.calypsan.listenup.client.features.bookedit

import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.design.components.rememberForwardingTextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.util.rememberUnsavedWorkGuard
import com.calypsan.listenup.client.design.theme.Spacing
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
import com.calypsan.listenup.client.presentation.bookedit.BookEditForm
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.features.edit.EditInlineError
import com.calypsan.listenup.client.features.edit.EditLoadFailed
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import com.calypsan.listenup.client.util.rememberImagePicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_classification
import listenup.composeapp.generated.resources.book_edit_enter_a_description
import listenup.composeapp.generated.resources.book_edit_identifiers
import listenup.composeapp.generated.resources.book_edit_publishing
import listenup.composeapp.generated.resources.book_edit_talent
import listenup.composeapp.generated.resources.common_description
import listenup.composeapp.generated.resources.common_library
import listenup.composeapp.generated.resources.common_series
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.common_save_changes
import listenup.composeapp.generated.resources.common_saving
import listenup.composeapp.generated.resources.book_edit_description_label

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

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val editing = uiState as? BookEditUiState.Editing

    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { navAction ->
            when (navAction) {
                is BookEditNavAction.NavigateBack -> onBackClick()
                is BookEditNavAction.ShowSaveSuccess -> onBackClick()
            }
        }
    }

    val requestLeave = rememberUnsavedWorkGuard(dirty = editing?.hasChanges == true, onLeave = onBackClick)

    ListenUpScaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (editing != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    ListenUpButton(
                        text =
                            if (editing.isSaving) {
                                stringResource(
                                    Res.string.common_saving,
                                )
                            } else {
                                stringResource(Res.string.common_save_changes)
                            },
                        onClick = { viewModel.onEvent(BookEditUiEvent.Save) },
                        enabled = editing.hasChanges && !editing.isSaving,
                        isLoading = editing.isSaving,
                        leadingIcon = ListenUpIcons.SaveFilled,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Content
            when (val current = uiState) {
                BookEditUiState.Loading -> {
                    ListenUpLoadingIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                is BookEditUiState.LoadFailed -> {
                    EditLoadFailed(
                        error = current.error,
                        onBack = onBackClick,
                        onRetry = { viewModel.onEvent(BookEditUiEvent.RetryLoad) },
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(paddingValues),
                    )
                }

                is BookEditUiState.Editing -> {
                    BookEditContent(
                        bookId = bookId,
                        studio = current,
                        onEvent = viewModel::onEvent,
                        onBackClick = requestLeave,
                        // consumeWindowInsets so the inner Column's imePadding() doesn't double-count the
                        // Scaffold's already-reserved bottom inset (Save bar + nav/mini-player) — otherwise a
                        // surface-colored band appears between the field and the keyboard.
                        modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
                    )
                }
            }
        }
    }
}

// =============================================================================
// CONTENT LAYOUT
// =============================================================================

@Suppress("UnusedParameter")
@Composable
private fun BookEditContent(
    bookId: String,
    studio: BookEditUiState.Editing,
    onEvent: (BookEditUiEvent) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = studio.form
    // Detect window size for responsive layout
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
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
            sortTitle = state.sortTitle,
            isUploadingCover = studio.isUploadingCover,
            onTitleChange = { onEvent(BookEditUiEvent.TitleChanged(it)) },
            onSubtitleChange = { onEvent(BookEditUiEvent.SubtitleChanged(it)) },
            onSortTitleChange = { onEvent(BookEditUiEvent.SortTitleChanged(it)) },
            onCoverClick = { imagePicker.launch() },
            onBackClick = onBackClick,
            bookId = bookId,
            coverHash = state.coverHash,
        )

        // A Save or cover failure sits above the cards, the edits below it untouched.
        studio.inlineError?.let { error ->
            EditInlineError(
                error = error,
                onDismiss = { onEvent(BookEditUiEvent.DismissError) },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }

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
    state: BookEditForm,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val descriptionState =
            rememberForwardingTextFieldState(state.description) { onEvent(BookEditUiEvent.DescriptionChanged(it)) }
        // People-first order (matches the flat iOS form): Description → Talent → Series →
        // Classification → Publishing → Identifiers → Library. Sort Title lives in the header.

        // Description (The Hook)
        StudioCard(title = stringResource(Res.string.common_description)) {
            ListenUpTextArea(
                state = descriptionState,
                label = stringResource(Res.string.book_edit_description_label),
                placeholder = stringResource(Res.string.book_edit_enter_a_description),
            )
        }

        // Talent (contributors) — who made it comes right after the hook
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

        // Series
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

        // Classification (genres, tags, moods, collections)
        ClassificationCard(state = state, onEvent = onEvent)

        // Publishing
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

        // Identifiers & Format
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

        // Library (Date Added)
        StudioCard(title = stringResource(Res.string.common_library)) {
            LibrarySection(
                addedAt = state.addedAt,
                onAddedAtChange = { onEvent(BookEditUiEvent.AddedAtChanged(it)) },
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
    state: BookEditForm,
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
            isHeld = state.isHeld,
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
 * Left Column: Talent, Series, Classification (positions 6–11 of the people-first order)
 * Right Column: Publishing, Identifiers, Library (positions 12–18)
 */
@Suppress("LongMethod")
@Composable
private fun TwoColumnCardsLayout(
    state: BookEditForm,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier.padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val descriptionState =
            rememberForwardingTextFieldState(state.description) { onEvent(BookEditUiEvent.DescriptionChanged(it)) }
        // Full-width: Description (The Hook) - Primary content
        StudioCard(title = stringResource(Res.string.common_description)) {
            ListenUpTextArea(
                state = descriptionState,
                label = stringResource(Res.string.book_edit_description_label),
                placeholder = stringResource(Res.string.book_edit_enter_a_description),
            )
        }

        // Two-column grid for remaining cards
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Left Column - People & content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
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

                ClassificationCard(state = state, onEvent = onEvent)
            }

            // Right Column - Catalog metadata
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
                        addedAt = state.addedAt,
                        onAddedAtChange = { onEvent(BookEditUiEvent.AddedAtChanged(it)) },
                    )
                }
            }
        }
    }
}
