package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.features.bookdetail.BookDetailActions
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailRows

/** Description lines on a phone before "Read more": four while deciding, two otherwise (spec §3.2.1 D29, §3.2.2). */
private const val ABOUT_LINES_NOT_STARTED = 4
private const val ABOUT_LINES = 2

/**
 * The sections a page shows, in the visit's stage order (spec §2.4). A held book is triage-only (§3.2.3): it keeps
 * About, Chapters and Details, and its social sections (Ratings, Readers, Hardcover) and Series stay hidden.
 */
internal fun BookDetailUiState.Ready.shownSections(): List<BookDetailSection> =
    if (isHeld) sectionOrder.filter { it.showsWhenHeld() } else sectionOrder

private fun BookDetailSection.showsWhenHeld(): Boolean =
    this == BookDetailSection.About || this == BookDetailSection.Chapters || this == BookDetailSection.Details

/**
 * The description expansion both layouts hold for the page, so a section renders the same on either.
 *
 * @property isExpanded Whether the synopsis is open past its clamp.
 * @property onToggle Opens or closes it.
 */
internal data class DescriptionExpansion(
    val isExpanded: Boolean,
    val onToggle: () -> Unit,
)

/**
 * Home's finishers line landing on Readers (spec §6.4): once the section is there, its heading takes focus.
 *
 * @property focusHeading Move focus (and TalkBack's) to the Readers heading once it is drawn.
 * @property onHeadingFocused Called once the heading has taken that focus.
 */
internal data class ReadersLanding(
    val focusHeading: Boolean = false,
    val onHeadingFocused: () -> Unit = {},
)

/**
 * Renders one Book Detail section, the same on the phone list and in the wide columns. A section with nothing to show
 * renders nothing (spec §2.4: skipped, not left as a gap): Readers, Ratings and Hardcover gate themselves; Series and
 * Chapters are already dropped from the order by the ViewModel when absent.
 *
 * @param isWide True in the wide columns, where the synopsis shows in full.
 * @param onShowAllCredits Opens the full cast: the Details row "All credits".
 */
@Suppress("LongParameterList")
@Composable
internal fun BookDetailSectionContent(
    section: BookDetailSection,
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument>,
    isWide: Boolean,
    description: DescriptionExpansion,
    modifier: Modifier = Modifier,
    readersLanding: ReadersLanding = ReadersLanding(),
    onShowAllCredits: () -> Unit = {},
) {
    when (section) {
        BookDetailSection.Chapters -> {
            BookChaptersSection(
                chapters = state.chapters,
                preview = state.chapterPreview,
                currentChapter = state.currentChapter,
                canPlay = state.canPlay,
                onPlayFromChapter = actions.onPlayFromChapter,
                modifier = modifier,
            )
        }

        BookDetailSection.About -> {
            AboutSection(
                description = state.descriptionText,
                genres = state.genres,
                tags = state.tags,
                moods = state.moods,
                isLoadingTags = state.isLoadingTags,
                isCard = true,
                collapsedLines =
                    when {
                        isWide -> null
                        state.readingStage == ReadingStage.NotStarted -> ABOUT_LINES_NOT_STARTED
                        else -> ABOUT_LINES
                    },
                isDescriptionExpanded = description.isExpanded,
                onToggleDescriptionExpanded = description.onToggle,
                onGenreClick = { genre -> actions.onGenreClick(genre.id) },
                onTagClick = { tag -> actions.onTagClick(tag.id, tag.displayName()) },
                onMoodClick = { mood -> actions.onMoodClick(mood.id, mood.displayName()) },
                modifier = modifier.fillMaxWidth(),
            )
        }

        BookDetailSection.Readers -> {
            BookReadersSection(
                bookId = bookId,
                onUserClick = actions.onUserProfileClick,
                isCard = true,
                onSeeAllClick = actions.onSeeAllReaders,
                focusHeadingOnArrival = readersLanding.focusHeading,
                onHeadingFocused = readersLanding.onHeadingFocused,
                modifier = modifier.fillMaxWidth(),
            )
        }

        BookDetailSection.Ratings -> {
            BookRatingBlock(
                bookId = bookId,
                isCard = true,
                isFinished = state.readingStage == ReadingStage.Finished,
                modifier = modifier.fillMaxWidth(),
            )
        }

        BookDetailSection.Hardcover -> {
            BookHardcoverSection(
                bookId = bookId,
                onFindMatch = actions.onFindHardcoverMatch,
                modifier = modifier.fillMaxWidth(),
            )
        }

        // The Details rows, ending in All credits, Files & audio and Maps & illustrations (spec D31, D32, D36).
        BookDetailSection.Details -> {
            DetailsSection(
                rows = remember(state, documents) { bookDetailRows(state, documents) },
                onAllCredits = onShowAllCredits,
                onFilesAndAudio = actions.onOpenFilesAndAudio,
                onMaps = { row -> row.onlyDocumentId?.let(actions.onOpenDocument) ?: actions.onOpenDocuments() },
                modifier = modifier,
            )
        }
    }
}
