package com.calypsan.listenup.web.features.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedBulkEdit(
    state: BulkEditUiState,
    events: Flow<BulkEditEvent> = emptyFlow(),
    genres: List<Genre> = emptyList(),
    tags: List<Tag> = emptyList(),
    moods: List<Mood> = emptyList(),
    seriesMatches: List<SeriesSearchResult> = emptyList(),
    contributorMatches: List<ContributorSearchResult> = emptyList(),
    onSeriesQuery: (String) -> Unit = {},
    onContributorQuery: (String) -> Unit = {},
    onPublisher: (String) -> Unit = {},
    onYear: (Int?) -> Unit = {},
    onLanguage: (String) -> Unit = {},
    onSeries: (BookSeriesInput?) -> Unit = {},
    onContributors: (List<BookContributorInput>) -> Unit = {},
    onGenres: (List<BookGenreInput>) -> Unit = {},
    onTags: (List<String>) -> Unit = {},
    onMoods: (List<String>) -> Unit = {},
    onApply: () -> Unit = {},
): OpenBulkEdit =
    {
        BulkEditSession(
            state = MutableStateFlow(state),
            events = events,
            genres = MutableStateFlow(genres),
            tags = MutableStateFlow(tags),
            moods = MutableStateFlow(moods),
            seriesMatches = MutableStateFlow(seriesMatches),
            contributorMatches = MutableStateFlow(contributorMatches),
            onSeriesQuery = onSeriesQuery,
            onContributorQuery = onContributorQuery,
            onPublisher = onPublisher,
            onYear = onYear,
            onLanguage = onLanguage,
            onSeries = onSeries,
            onContributors = onContributors,
            onGenres = onGenres,
            onTags = onTags,
            onMoods = onMoods,
            onApply = onApply,
            close = {},
        )
    }
