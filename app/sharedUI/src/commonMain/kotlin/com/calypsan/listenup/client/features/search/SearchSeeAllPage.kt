@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.features.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.LoadFailed
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_people
import listenup.composeapp.generated.resources.shell_close_search
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Which single-type "See all" page is open, if any — saveable, so rotating on a See-all page stays on
 * it. Saved by the type's name, so the enum never enters the saved state itself.
 */
@Composable
internal fun rememberSeeAllType(): MutableState<SearchHitType?> =
    rememberSaveable(stateSaver = SeeAllTypeSaver) { mutableStateOf(null) }

private val SeeAllTypeSaver: Saver<SearchHitType?, String> =
    Saver(
        save = { type -> type?.name },
        restore = { saved -> SearchHitType.entries.firstOrNull { it.name == saved } },
    )

/**
 * Full-bleed single-type "See all" page, reached from a capped group's header in the main results
 * view. Shows a back-arrow top bar with the type's title, then the complete list of that type's
 * hits rendered with the same row composables the overlay uses. Binds its own
 * [SeeAllSearchViewModel] and loads the full list for [query] + [type] on entry; result taps route
 * through [onResultClick] (the same nav path the overlay threads), so AppShell stays the single
 * navigation owner.
 */
@Composable
internal fun SearchSeeAllPage(
    query: String,
    type: SearchHitType,
    onBack: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SeeAllSearchViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(query, type) {
        viewModel.load(query, type)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SeeAllTopBar(title = seeAllTitle(type), onBack = onBack)

            when (val current = state) {
                is SeeAllSearchUiState.Idle,
                is SeeAllSearchUiState.Loading,
                -> {
                    LoadingState(modifier = Modifier.weight(1f))
                }

                is SeeAllSearchUiState.TooShort -> {
                    TooShortState(modifier = Modifier.weight(1f))
                }

                is SeeAllSearchUiState.Error -> {
                    LoadFailed(message = current.message, onRetry = viewModel::retry, modifier = Modifier.weight(1f))
                }

                is SeeAllSearchUiState.Results -> {
                    if (current.hits.isEmpty()) {
                        NoResultsState(query = query, modifier = Modifier.weight(1f))
                    } else {
                        SeeAllList(
                            type = type,
                            hits = current.hits,
                            query = query,
                            onResultClick = onResultClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Top bar for the See-all page: a back arrow and the type's title. */
@Composable
private fun SeeAllTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                haptics.press()
                onBack()
            },
        ) {
            Icon(
                imageVector = ListenUpIcons.ArrowBackFilled,
                contentDescription = stringResource(Res.string.shell_close_search),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * The full list for one type. Tags get a wrapping pill flow; the other types reuse the same row
 * composables as the main overlay. A capped book/series/contributor list reads well at expanded
 * width inside a width-constrained column.
 */
@Composable
private fun SeeAllList(
    type: SearchHitType,
    hits: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = Spacing.screenMargin,
                end = Spacing.screenMargin,
                top = Spacing.sm,
                bottom = Spacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (type) {
            SearchHitType.BOOK -> {
                items(
                    items = hits,
                    key = { hitKey(BOOK_KEY_PREFIX, it) },
                    contentType = { CONTENT_TYPE_BOOK_ROW },
                ) { hit ->
                    BookResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.CONTRIBUTOR -> {
                items(
                    items = hits,
                    key = { hitKey(CONTRIBUTOR_KEY_PREFIX, it) },
                    contentType = { CONTENT_TYPE_PERSON_ROW },
                ) { hit ->
                    PersonResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.SERIES -> {
                items(
                    items = hits,
                    key = { hitKey(SERIES_KEY_PREFIX, it) },
                    contentType = { CONTENT_TYPE_SERIES_ROW },
                ) { hit ->
                    SeriesResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.TAG -> {
                item(key = TAGS_FLOW_KEY, contentType = CONTENT_TYPE_TAG_FLOW) {
                    TagFlow(tags = hits, query = query, onResultClick = onResultClick)
                }
            }
        }
    }
}

/** The localized page title for a single-type See-all page. */
@Composable
private fun seeAllTitle(type: SearchHitType): String =
    when (type) {
        SearchHitType.BOOK -> stringResource(Res.string.library_books)
        SearchHitType.CONTRIBUTOR -> stringResource(Res.string.search_people)
        SearchHitType.SERIES -> stringResource(Res.string.common_series)
        SearchHitType.TAG -> stringResource(Res.string.book_detail_tags)
    }
