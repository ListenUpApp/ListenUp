package com.calypsan.listenup.web.features.metadata

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Match metadata — find this book on Audible, then choose what to take from it.
 *
 * Pure in [state]; the store wiring lives one level up. The wizard is two phases and the state
 * says which: [MetadataUiState.Search] is looking, [MetadataUiState.Preview] is deciding. The
 * reader can go back from the second to the first without re-running the search, which is why the
 * results ride along in the preview state rather than being dropped.
 *
 * **Nothing is written until Apply, and only what is ticked is written.** A match is a suggestion.
 */
@Suppress("LongParameterList")
@Composable
fun MetadataPage(
    state: MetadataUiState,
    onQuery: (String) -> Unit,
    onRegion: (MetadataLocale) -> Unit,
    onSearch: () -> Unit,
    onSelectMatch: (MetadataBook) -> Unit,
    onClearSelection: () -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSelectCover: (String?) -> Unit,
    onToggleChapter: (Int) -> Unit,
    onApplyChapterNames: () -> Unit,
    onApply: () -> Unit,
    onLeave: () -> Unit,
    reviewingChapters: Boolean = false,
    onReviewChapters: (Boolean) -> Unit = {},
) {
    Div(attrs = { classes("mdx") }) {
        Div(attrs = { classes("mdx-head") }) {
            H1(attrs = { classes("mdx-t") }) { Text("Match metadata") }
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                onClick { onLeave() }
            }) { Text("Back") }
        }

        when (state) {
            is MetadataUiState.Idle -> {
                Div(attrs = { classes("skel", "mdx-skel") })
            }

            is MetadataUiState.Search -> {
                MetadataSearchPhase(
                    state = state,
                    onQuery = onQuery,
                    onRegion = onRegion,
                    onSearch = onSearch,
                    onSelectMatch = onSelectMatch,
                )
            }

            is MetadataUiState.Preview -> {
                PreviewPhase(
                    state = state,
                    onRegion = onRegion,
                    onClearSelection = onClearSelection,
                    onToggleField = onToggleField,
                    onToggleAuthor = onToggleAuthor,
                    onToggleNarrator = onToggleNarrator,
                    onToggleSeries = onToggleSeries,
                    onToggleGenre = onToggleGenre,
                    onToggleMood = onToggleMood,
                    onToggleTag = onToggleTag,
                    onSelectCover = onSelectCover,
                    onToggleChapter = onToggleChapter,
                    onApplyChapterNames = onApplyChapterNames,
                    onApply = onApply,
                    reviewingChapters = reviewingChapters,
                    onReviewChapters = onReviewChapters,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun PreviewPhase(
    state: MetadataUiState.Preview,
    onRegion: (MetadataLocale) -> Unit,
    onClearSelection: () -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onSelectCover: (String?) -> Unit,
    onToggleChapter: (Int) -> Unit,
    onApplyChapterNames: () -> Unit,
    onApply: () -> Unit,
    reviewingChapters: Boolean,
    onReviewChapters: (Boolean) -> Unit,
) {
    when (val load = state.loadState) {
        PreviewLoadState.Loading -> {
            Div(attrs = { classes("skel", "mdx-skel") })
        }

        is PreviewLoadState.Failed -> {
            P(attrs = {
                classes("mdx-err")
                attr("role", "alert")
            }) { Text(load.message) }
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                onClick { onClearSelection() }
            }) { Text("Back to results") }
        }

        is PreviewLoadState.Ready -> {
            MetadataPreviewPhase(
                ready = load,
                region = state.region,
                onRegion = onRegion,
                onBackToResults = onClearSelection,
                onToggleField = onToggleField,
                onToggleAuthor = onToggleAuthor,
                onToggleNarrator = onToggleNarrator,
                onToggleSeries = onToggleSeries,
                onToggleGenre = onToggleGenre,
                onToggleMood = onToggleMood,
                onToggleTag = onToggleTag,
                onSelectCover = onSelectCover,
                onReviewChapters = { onReviewChapters(true) },
                onApply = onApply,
            )

            val available = load.chapterSuggestion as? ChapterSuggestion.Available
            if (reviewingChapters && available != null) {
                ChapterNamesDialog(
                    available = available,
                    onToggleChapter = onToggleChapter,
                    onApply = onApplyChapterNames,
                    onDismiss = { onReviewChapters(false) },
                )
            }
        }
    }
}
