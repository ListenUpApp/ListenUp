package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.home.HeroChapter
import com.calypsan.listenup.client.presentation.home.HomeSlot
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapter_label
import listenup.composeapp.generated.resources.book_detail_resume
import listenup.composeapp.generated.resources.book_time_left
import listenup.composeapp.generated.resources.common_percent
import listenup.composeapp.generated.resources.home_next_in_series
import listenup.composeapp.generated.resources.home_progress_of_length
import listenup.composeapp.generated.resources.home_resume_unavailable_offline
import listenup.composeapp.generated.resources.home_up_next
import listenup.composeapp.generated.resources.library_card_progress_a11y
import listenup.composeapp.generated.resources.library_card_read_by
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** Test tag for the hero's link into the book (one link, one button; never nested, H15). */
internal const val HOME_RESUME_IDENTITY_TAG = "home-resume-identity"

/** Test tag for the hero's Resume button. */
internal const val HOME_RESUME_BUTTON_TAG = "home-resume-button"

private val HeroCover = 120.dp
private val OnlyBookCover = 176.dp
private val NextCover = 44.dp
private val ResumeHeight = 56.dp

/**
 * The Resume kind (spec §2.3): nothing is loaded and [resume]'s book is the one last played. The cover and title are
 * one link into the book; Resume is a separate button that plays it from its saved position (C18). [isOnlyBook] is
 * the "one book in progress" variant: the same content around a larger, centred cover.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeResumeHero(
    resume: HomeSlot.Resume,
    isOnlyBook: Boolean,
    onOpenBook: (String) -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = resume.book
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            HeroIdentity(book, isOnlyBook, onOpen = { onOpenBook(book.bookId) })
            HeroProgress(book, resume.chapter)
            resume.next?.let { next -> NextInSeriesRow(next, onOpen = { onOpenBook(next.bookId) }) }
            Button(
                onClick = { onResume(book.bookId) },
                enabled = resume.canPlay,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = ResumeHeight).testTag(HOME_RESUME_BUTTON_TAG),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.book_detail_resume))
            }
            if (!resume.canPlay) {
                Text(
                    text = stringResource(Res.string.home_resume_unavailable_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Cover, eyebrow, title, author and narrators: one link, one tab stop (§8.2). */
@Composable
private fun HeroIdentity(
    book: ContinueListeningBook,
    isOnlyBook: Boolean,
    onOpen: () -> Unit,
) {
    val link =
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onOpen)
            .testTag(HOME_RESUME_IDENTITY_TAG)
    if (isOnlyBook) {
        Column(
            link,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            HeroCoverImage(book, OnlyBookCover)
            HeroTexts(book, TextAlign.Center)
        }
    } else {
        Row(
            link,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroCoverImage(book, HeroCover)
            HeroTexts(book, TextAlign.Start)
        }
    }
}

/** "Up next · 2h ago" (C14), the title, the author and "Read by …". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroTexts(
    book: ContinueListeningBook,
    align: TextAlign,
) {
    Column(horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            text = stringResource(Res.string.home_up_next, relativeTime(book.lastPlayedAtMs)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.authorNames.isNotBlank()) {
            Text(
                text = book.authorNames,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = align,
            )
        }
        book.narratorNames?.takeIf { it.isNotBlank() }?.let { narrators ->
            Text(
                text = stringResource(Res.string.library_card_read_by, narrators),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = align,
            )
        }
    }
}

@Composable
private fun HeroCoverImage(
    book: ContinueListeningBook,
    size: Dp,
) {
    Box {
        BookCoverImage(
            bookId = book.bookId,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            contentDescription = null,
            title = book.title,
            author = book.authorNames,
            // Decorative: the link's text names the book, so the fallback's title is not read twice.
            modifier = Modifier.size(size).clip(ContentShapes.card).clearAndSetSemantics {},
        )
        RestrictedBookMarker(book.bookId, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
    }
}

/** "Ch. 6 · Bridge Four" with the time left, a flat line (never wavy on Home, F7), and "11% of 45h 30m". */
@Composable
private fun HeroProgress(
    book: ContinueListeningBook,
    chapter: HeroChapter?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text =
                    chapter
                        ?.let { current ->
                            stringResource(
                                Res.string.book_detail_chapter_label,
                                current.number,
                                current.title,
                            )
                        }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        Res.string.book_time_left,
                        DurationFormatter.hoursMinutes(book.timeRemainingMs.milliseconds),
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val percentLabel = stringResource(Res.string.library_card_progress_a11y, book.progressPercent)
        LinearProgressIndicator(
            progress = { book.progress },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = percentLabel },
        )
        Text(
            text =
                stringResource(
                    Res.string.home_progress_of_length,
                    stringResource(Res.string.common_percent, book.progressPercent),
                    DurationFormatter.hoursMinutes(book.totalDurationMs.milliseconds),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Next in the series" (§2.3, shared by the Resume and finish cards): an opaque list row on `surfaceContainerLowest`, no chevron, that opens that book. */
@Composable
internal fun NextInSeriesRow(
    next: NextInSeries,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                BookCoverImage(
                    bookId = next.bookId,
                    coverPath = next.coverPath,
                    coverHash = next.coverHash,
                    contentDescription = null,
                    title = next.title,
                    // Decorative: the row's text names the book.
                    modifier = Modifier.size(NextCover).clip(MaterialTheme.shapes.small).clearAndSetSemantics {},
                )
                RestrictedBookMarker(next.bookId, Modifier.align(Alignment.TopStart), compact = true)
            }
            Column {
                Text(
                    text = stringResource(Res.string.home_next_in_series),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = next.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
