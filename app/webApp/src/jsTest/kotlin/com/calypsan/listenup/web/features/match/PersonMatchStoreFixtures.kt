package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A session over flows a spec owns, recording what it is asked to do. The gestures do not move the state on their
 * own — a spec that wants Review after a pick sets [reviewState] itself, as the server would.
 */
@Suppress("LongParameterList")
fun fixedPersonMatch(
    findState: StateFlow<PersonFindUiState>,
    reviewState: StateFlow<PersonReviewUiState> = MutableStateFlow(PersonReviewUiState.NoneChosen),
    events: Flow<PersonMatchEvent> = emptyFlow(),
    search: (String) -> Unit = {},
    retry: () -> Unit = {},
    pick: (PersonCandidateKey) -> Unit = {},
    backToResults: () -> Unit = {},
    useTwoPane: (Boolean) -> Unit = {},
    setPhotoTicked: (Boolean) -> Unit = {},
    choosePhoto: (ImageChoice) -> Unit = {},
    setBiographyTicked: (Boolean) -> Unit = {},
    chooseBiographySource: (FieldChoice) -> Unit = {},
    apply: () -> Unit = {},
): PersonMatchSession =
    PersonMatchSession(
        findState = findState,
        reviewState = reviewState,
        events = events,
        search = search,
        retry = retry,
        pick = pick,
        backToResults = backToResults,
        useTwoPane = useTwoPane,
        setPhotoTicked = setPhotoTicked,
        choosePhoto = choosePhoto,
        setBiographyTicked = setBiographyTicked,
        chooseBiographySource = chooseBiographySource,
        apply = apply,
        close = {},
    )
