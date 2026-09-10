package com.calypsan.listenup.web.features.metadata

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.BookContext
import com.calypsan.listenup.client.presentation.metadata.ChapterNameRow
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.CoverEntry
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import com.calypsan.listenup.client.presentation.metadata.SearchLoadState

@Suppress("LongParameterList")
internal fun metadataBook(
    asin: String = "B002V0QUOC",
    title: String = "The Way of Kings",
    subtitle: String? = null,
    description: String? = null,
    publisher: String? = null,
    releaseDate: String? = null,
    runtimeMinutes: Int? = null,
    language: String? = null,
    authors: List<String> = emptyList(),
    narrators: List<String> = emptyList(),
    series: List<Triple<String, String, String?>> = emptyList(),
    genres: List<String> = emptyList(),
    moods: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    coverUrl: String? = null,
) = MetadataBook(
    asin = asin,
    title = title,
    subtitle = subtitle,
    description = description,
    publisher = publisher,
    releaseDate = releaseDate,
    runtimeMinutes = runtimeMinutes,
    language = language,
    authors = authors.mapIndexed { i, name -> MetadataContributorRef(asin = "a$i", name = name) },
    narrators = narrators.mapIndexed { i, name -> MetadataContributorRef(asin = "n$i", name = name) },
    series = series.map { (id, name, seq) -> MetadataSeriesRef(asin = id, title = name, sequence = seq) },
    genres = genres,
    moods = moods,
    tags = tags,
    coverUrl = coverUrl,
    coverUrlMaxSize = null,
)

internal fun searchState(
    query: String = "The Way of Kings",
    loadState: SearchLoadState = SearchLoadState.Idle,
    region: MetadataLocale = MetadataLocale.DEFAULT,
    currentTitle: String = "The Way of Kings",
    currentAuthor: String = "Brandon Sanderson",
) = MetadataUiState.Search(
    region = region,
    context =
        BookContext(
            bookId = "b1",
            currentTitle = currentTitle,
            currentAuthor = currentAuthor,
            existingAsin = null,
        ),
    query = query,
    loadState = loadState,
)

@Suppress("LongParameterList")
internal fun readyPreview(
    preview: MetadataBook = metadataBook(),
    selections: MetadataSelections = MetadataSelections(),
    coverEntries: List<CoverEntry> = emptyList(),
    selectedCoverUrl: String? = null,
    isApplying: Boolean = false,
    applyError: String? = null,
    previewNotFound: Boolean = false,
    chapterSuggestion: ChapterSuggestion = ChapterSuggestion.Unavailable,
    genreCandidates: List<String> = emptyList(),
    moodCandidates: List<String> = emptyList(),
    tagCandidates: List<String> = emptyList(),
    fallbackSources: Map<BookField, String> = emptyMap(),
    coverSourceLabel: String? = null,
    contributingSources: List<String> = emptyList(),
) = PreviewLoadState.Ready(
    preview = preview,
    selections = selections,
    coverEntries = coverEntries,
    selectedCoverUrl = selectedCoverUrl,
    isApplying = isApplying,
    applyError = applyError,
    previewNotFound = previewNotFound,
    chapterSuggestion = chapterSuggestion,
    genreCandidates = genreCandidates,
    moodCandidates = moodCandidates,
    tagCandidates = tagCandidates,
    fallbackSources = fallbackSources,
    coverSourceLabel = coverSourceLabel,
    coverResolution = null,
    contributingSources = contributingSources,
)

internal fun previewState(
    loadState: PreviewLoadState,
    region: MetadataLocale = MetadataLocale.DEFAULT,
    match: MetadataBook = metadataBook(),
    searchResults: List<MetadataBook> = emptyList(),
) = MetadataUiState.Preview(
    region = region,
    context =
        BookContext(
            bookId = "b1",
            currentTitle = "The Way of Kings",
            currentAuthor = "Brandon Sanderson",
            existingAsin = null,
        ),
    query = "The Way of Kings",
    searchResults = searchResults,
    match = match,
    loadState = loadState,
)

internal fun chapterRows(count: Int): List<ChapterNameRow> =
    (0 until count).map {
        ChapterNameRow(ordinal = it, currentName = "Track ${it + 1}", suggestedName = "Chapter ${it + 1}")
    }

internal fun availableChapters(
    count: Int = 3,
    selected: Set<Int> = (0 until count).toSet(),
    isApplying: Boolean = false,
    applyError: String? = null,
) = ChapterSuggestion.Available(
    rows = chapterRows(count),
    selectedOrdinals = selected,
    isApplying = isApplying,
    applyError = applyError,
)
