package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.BookMatchEvent
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A session over flows a spec owns, recording what it is asked to do. The gestures do not move the state on
 * their own — a spec that wants Review after a pick sets [reviewState] itself, as the server would.
 */
@Suppress("LongParameterList")
fun fixedBookMatch(
    findState: StateFlow<FindUiState>,
    reviewState: StateFlow<ReviewUiState> = MutableStateFlow(ReviewUiState.NoneChosen),
    events: Flow<BookMatchEvent> = emptyFlow(),
    search: (String) -> Unit = {},
    searchByTitle: () -> Unit = {},
    chooseStoreForThisSearch: (MetadataLocale) -> Unit = {},
    retry: () -> Unit = {},
    pick: (BookCandidateKey) -> Unit = {},
    backToResults: () -> Unit = {},
    useTwoPane: (Boolean) -> Unit = {},
    setFieldTicked: (BookField, Boolean) -> Unit = { _, _ -> },
    chooseSource: (BookField, FieldChoice) -> Unit = { _, _ -> },
    chooseCover: (ImageChoice) -> Unit = {},
    removeYourLabel: (LabelKind, String) -> Unit = { _, _ -> },
    restoreYourLabel: (LabelKind, String) -> Unit = { _, _ -> },
    toggleSuggestion: (LabelKind, String) -> Unit = { _, _ -> },
    setChapterNamesIncluded: (Boolean) -> Unit = {},
    toggleChapter: (Int) -> Unit = {},
    apply: () -> Unit = {},
): BookMatchSession =
    BookMatchSession(
        findState = findState,
        reviewState = reviewState,
        events = events,
        search = search,
        searchByTitle = searchByTitle,
        chooseStoreForThisSearch = chooseStoreForThisSearch,
        retry = retry,
        pick = pick,
        backToResults = backToResults,
        useTwoPane = useTwoPane,
        setFieldTicked = setFieldTicked,
        chooseSource = chooseSource,
        chooseCover = chooseCover,
        removeYourLabel = removeYourLabel,
        restoreYourLabel = restoreYourLabel,
        toggleSuggestion = toggleSuggestion,
        setChapterNamesIncluded = setChapterNamesIncluded,
        toggleChapter = toggleChapter,
        apply = apply,
        close = {},
    )
