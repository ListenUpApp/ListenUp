package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.client.util.relativeOrMonthYear
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.book_detail_readers_finished
import listenup.composeapp.generated.resources.book_detail_reader_listening
import listenup.composeapp.generated.resources.book_detail_reader_listening_now
import listenup.composeapp.generated.resources.book_detail_readers_a11y_reading
import listenup.composeapp.generated.resources.book_detail_see_all_readers
import listenup.composeapp.generated.resources.book_detail_readers_reading
import listenup.composeapp.generated.resources.book_detail_readers_note
import listenup.composeapp.generated.resources.book_detail_readers_hardcover
import listenup.composeapp.generated.resources.book_detail_readers_also_on_hardcover
import listenup.composeapp.generated.resources.book_detail_readers_finished_also_on_hardcover
import listenup.composeapp.generated.resources.book_detail_readers_rated
import listenup.composeapp.generated.resources.book_detail_readers_read
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import listenup.composeapp.generated.resources.book_detail_readers_read_on_hardcover
import listenup.composeapp.generated.resources.book_detail_readers_count
import listenup.composeapp.generated.resources.book_detail_readers_count_one
import listenup.composeapp.generated.resources.book_detail_reader_view_profile
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable

/** Rows the section shows before "See all N readers" (spec §2.10: three, on every platform and width). */
private const val MAX_COLLAPSED_READERS = 3

/**
 * Presentation-only model for a single reader row in [BookReadersContent].
 *
 * Decouples the row UI from the sparse [Reader] domain model so the stateless content can be
 * rendered with rich mock data (active progress, finished dates) in an on-device gallery, while
 * the production path degrades gracefully to what the real model exposes today.
 *
 * @property userId Stable user identifier; forwarded to [UserAvatar] and the row click callback.
 * @property name Display name shown as the row title.
 * @property isReading `true` when the reader is actively listening — drives the active-ring avatar
 *   and the [Icons.Default.GraphicEq] trailing indicator. `false` renders the finished treatment.
 * @property progressPct Listening progress in `0..100` when reading *and* known; `null` when reading
 *   but the percentage is unknown — the ring + [Icons.Default.GraphicEq] still show, but no bar.
 * @property finishedWhen Human-readable completion marker (e.g. `"Apr 12"`) when finished; `null`
 *   while reading.
 * @property halfStars The person's rating (2..10) on their first row; `null` on later rows and for
 *   people who have not rated the book.
 * @property note The note left with [halfStars], if any.
 * @property isRatedOnly `true` for a person who rated the book without reading it here: the row
 *   reads "Rated", with no progress bar and no finished date.
 * @property isOnHardcover `true` for a read logged on Hardcover (#601 B3): the row reads "Read {date}"
 *   beside a "Hardcover" label, never "Finished".
 * @property finishedAtMs When a finished row's read was finished (epoch ms) — its identity in a list,
 *   since [finishedWhen] reads the same for two finishes in one month; `null` for other rows.
 * @property isAlsoOnHardcover `true` for a ListenUp finish the reader logged on Hardcover too: the one
 *   row reads "Finished {date}" beside an "Also on Hardcover" label, instead of a second Hardcover row.
 * @property isLive `true` only for your own reading row while the player is playing this book: "Listening now", the
 *   ring and the listening mark. Any other reading row says "Listening" over its bar: progress is not presence.
 */
data class ReaderRowUi(
    val userId: String,
    val name: String,
    val isReading: Boolean,
    val progressPct: Int?,
    val finishedWhen: String?,
    val halfStars: Int? = null,
    val note: String? = null,
    val isRatedOnly: Boolean = false,
    val isOnHardcover: Boolean = false,
    val finishedAtMs: Long? = null,
    val isAlsoOnHardcover: Boolean = false,
    val isLive: Boolean = false,
)

/**
 * The row's identity in a lazy list — a duplicate key crashes the list. Two finishes in the same month
 * read the same, so a finished row is keyed by the instant it was finished, not by what it shows; and
 * a ListenUp finish and a Hardcover read at the same instant are two rows, so where it was logged counts too.
 */
internal val ReaderRowUi.listKey: String
    get() = "$userId:$isReading:$isOnHardcover:${finishedAtMs ?: finishedWhen}"

/**
 * Flattens readers into [ReaderRowUi] rows for both the capped Book Detail section and the full
 * [com.calypsan.listenup.client.features.bookreaders.BookReadersScreen]. Reading lines come first,
 * then finished lines newest-first (see [flattenToLines]); the caller's own row is labelled "You".
 *
 * @param nowMs Current epoch-ms reference, passed to [relativeOrMonthYear] for finished dates.
 */
internal fun List<ReaderLine>.toReaderRows(nowMs: Long): List<ReaderRowUi> =
    map { line ->
        val name = if (line.isYou) "You" else line.name
        when (val k = line.kind) {
            is ReaderLineKind.Reading -> {
                ReaderRowUi(
                    userId = line.userId,
                    name = name,
                    isReading = true,
                    isLive = k.isLive,
                    progressPct = k.progressPct,
                    finishedWhen = null,
                    halfStars = line.rating?.halfStars,
                    note = line.rating?.note,
                )
            }

            is ReaderLineKind.Finished -> {
                ReaderRowUi(
                    userId = line.userId,
                    name = name,
                    isReading = false,
                    progressPct = null,
                    finishedWhen = relativeOrMonthYear(k.finishedAtMs, nowMs),
                    halfStars = line.rating?.halfStars,
                    note = line.rating?.note,
                    finishedAtMs = k.finishedAtMs,
                    isAlsoOnHardcover = k.alsoOnHardcover,
                )
            }

            is ReaderLineKind.FinishedOnHardcover -> {
                ReaderRowUi(
                    userId = line.userId,
                    name = name,
                    isReading = false,
                    progressPct = null,
                    finishedWhen = relativeOrMonthYear(k.finishedAtMs, nowMs),
                    halfStars = line.rating?.halfStars,
                    note = line.rating?.note,
                    finishedAtMs = k.finishedAtMs,
                    isOnHardcover = true,
                )
            }

            ReaderLineKind.Rated -> {
                ReaderRowUi(
                    userId = line.userId,
                    name = name,
                    isReading = false,
                    progressPct = null,
                    finishedWhen = null,
                    halfStars = line.rating?.halfStars,
                    note = line.rating?.note,
                    isRatedOnly = true,
                )
            }
        }
    }

/**
 * VM-bound entry point for the Readers section on the Book Detail screen.
 *
 * Collects [BookReadersViewModel] state and, on [BookReadersUiState.Data], maps each reader's
 * state onto [ReaderRowUi] before delegating to the stateless [BookReadersContent]. Loading and
 * Error states render nothing — the Readers section is non-critical.
 *
 * note: per-reader progress % is not yet shown. The current user's own row reflects whether they
 * are reading or have finished (with a completion date); other readers come from live presence so
 * they always read as actively listening. Past other-readers and per-reader progress % need richer
 * server data.
 *
 * @param bookId The book ID to load readers for.
 * @param onUserClick Callback when a reader row is clicked (navigates to user profile).
 * @param modifier Optional modifier.
 * @param isCard When true, wraps the rendered content in a `surfaceContainerLow` card; otherwise
 *   renders frameless.
 * @param focusHeadingOnArrival Move focus (and with it TalkBack's) to the Readers heading once it is drawn: Home's
 *   finishers line lands here (spec §6.4).
 * @param onHeadingFocused Called once the heading has taken that focus.
 * @param viewModel The ViewModel for loading readers data; scoped to [bookId].
 */
@Composable
fun BookReadersSection(
    bookId: String,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isCard: Boolean = false,
    onSeeAllClick: (String) -> Unit = {},
    focusHeadingOnArrival: Boolean = false,
    onHeadingFocused: () -> Unit = {},
    viewModel: BookReadersViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Loading and Error render nothing — the Readers section is non-critical.
    val data = state as? BookReadersUiState.Data ?: return

    val nowMs = Clock.System.now().toEpochMilliseconds()
    val allRows = data.lines.toReaderRows(nowMs)
    val rows = allRows.take(MAX_COLLAPSED_READERS)

    BookReadersContent(
        readers = rows,
        readingCount = allRows.count { it.isReading },
        totalCount = allRows.size,
        isCard = isCard,
        onUserClick = onUserClick,
        onSeeAllClick = { onSeeAllClick(bookId) },
        modifier = modifier,
        focusHeadingOnArrival = focusHeadingOnArrival,
        onHeadingFocused = onHeadingFocused,
    )
}

/**
 * Stateless content for the Readers section.
 *
 * Renders a header (title + [CountBadge] + "See all"), a "N listening now" sub-line, and a
 * [ReaderRow] per entry. Renders nothing when [readers] is empty, so no hollow card is drawn.
 *
 * On wide layouts ([isCard] = true) the content is wrapped in a `surfaceContainerLow` card with
 * [ContentShapes.card] shape and [Spacing.screenMargin] inner padding — mirroring [AboutSection].
 * On compact layouts ([isCard] = false) it renders frameless, mirroring [ChaptersSection].
 *
 * @param readers The reader rows to render; an empty list renders nothing.
 * @param readingCount Count shown in the "N reading" sub-line: readers partway through.
 * @param totalCount Total reader count shown in the [CountBadge]; gates the "See all" affordance.
 * @param isCard When true, wraps the content in a `surfaceContainerLow` card.
 * @param onUserClick Callback when a reader row is clicked.
 * @param onSeeAllClick Callback for the "See all" affordance, shown only when [totalCount] exceeds
 *   the number of rendered rows.
 * @param modifier Optional modifier.
 * @param focusHeadingOnArrival Move focus to the heading once it is drawn (the Readers landing, spec §6.4).
 * @param onHeadingFocused Called once the heading has taken that focus.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookReadersContent(
    readers: List<ReaderRowUi>,
    readingCount: Int,
    totalCount: Int,
    isCard: Boolean,
    onUserClick: (String) -> Unit,
    onSeeAllClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    focusHeadingOnArrival: Boolean = false,
    onHeadingFocused: () -> Unit = {},
) {
    val haptics = LocalHaptics.current
    if (readers.isEmpty()) return

    val headingFocus = remember { FocusRequester() }
    val currentOnHeadingFocused by rememberUpdatedState(onHeadingFocused)
    LaunchedEffect(focusHeadingOnArrival) {
        if (!focusHeadingOnArrival) return@LaunchedEffect
        // After the frame that places the heading, so the requester is attached and the scroll has settled.
        withFrameNanos { }
        headingFocus.requestFocus()
        currentOnHeadingFocused()
    }

    val innerPadding = if (isCard) Spacing.screenMargin else 0.dp

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding)) {
            // Header row: title + CountBadge + spacer + "See all"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title and its count are one heading: "Readers, 3 readers". See all keeps its own node.
                Row(
                    modifier =
                        Modifier
                            .focusRequester(headingFocus)
                            .focusable()
                            .semantics(mergeDescendants = true) { heading() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.book_detail_readers),
                        // Medium: the hero title is the screen's one display moment (rev 7 type rule).
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    CountBadge(count = totalCount, contentDescription = readerCount(totalCount))
                }
            }

            // Sub-line: "N reading" — people partway through. Not "listening now": progress is not presence.
            if (readingCount > 0) {
                Text(
                    text = stringResource(Res.string.book_detail_readers_reading, readingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            readers.forEach { reader ->
                ReaderRow(
                    reader = reader,
                    onUserClick = onUserClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Below the rows, full width, as the board draws it: the way to everyone past the first three.
            if (totalCount > readers.size) {
                FilledTonalButton(
                    onClick = {
                        haptics.press()
                        onSeeAllClick()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                ) {
                    Text(stringResource(Res.string.book_detail_see_all_readers, totalCount))
                }
            }
        }
    }

    if (isCard) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ContentShapes.card,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * A single reader row.
 *
 * When [ReaderRowUi.isReading] is true the avatar gets a 2.5dp [MaterialTheme.colorScheme.primary]
 * ring offset 2dp from the circle, a thin progress bar + percentage label show when
 * [ReaderRowUi.progressPct] is known, and a trailing [Icons.Default.GraphicEq] indicates active
 * listening. When finished, a "Finished {when}" line shows under the name with a trailing
 * [Icons.Default.CheckCircle]. A rated-only row reads "Rated" with a trailing star. When the row
 * carries a rating, small stars sit beside the name and the note, in quotes, beneath it.
 *
 * @param reader The reader row model to display.
 * @param onUserClick Callback when the row is clicked.
 * @param modifier Optional modifier.
 */
@Composable
internal fun ReaderRow(
    reader: ReaderRowUi,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clickable(onClickLabel = stringResource(Res.string.book_detail_reader_view_profile)) {
                    onUserClick(reader.userId)
                }.padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Live ring: a 2.5dp primary border offset 2dp from the avatar, only while you are listening right now.
        val avatarModifier =
            if (reader.isLive) {
                Modifier
                    .padding(2.5.dp)
                    .border(
                        width = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ).padding(2.dp)
            } else {
                Modifier
            }

        // The name is read beside it; the avatar saying it first was "Ada, Ada".
        UserAvatar(
            userId = reader.userId,
            size = AvatarSize.Medium,
            modifier = Modifier.clearAndSetSemantics {}.then(avatarModifier),
        )

        Spacer(modifier = Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reader.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (reader.halfStars != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RatingStars(halfStars = reader.halfStars)
                }
            }

            ReaderStateLine(reader)

            if (reader.note != null) {
                Text(
                    text = stringResource(Res.string.book_detail_readers_note, reader.note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // The trailing mark: listening (live only), rated or finished. Partway through but not live has none — the
        // bar says how far, and nothing claims they are listening.
        val mark =
            when {
                reader.isLive -> Icons.Default.GraphicEq
                reader.isReading -> null
                reader.isRatedOnly -> Icons.Rounded.Star
                else -> Icons.Default.CheckCircle
            }
        if (mark != null) {
            Icon(
                imageVector = mark,
                contentDescription = null,
                tint = if (reader.isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The line under a reader's name: progress while they are reading, "Read {date}" beside a "Hardcover"
 * label for a read logged there, "Finished {date}" for a ListenUp finish — beside an "Also on
 * Hardcover" label when it was logged there too — or "Rated".
 */
@Composable
private fun ReaderStateLine(reader: ReaderRowUi) {
    if (reader.isReading) {
        Text(
            text =
                stringResource(
                    if (reader.isLive) Res.string.book_detail_reader_listening_now else Res.string.book_detail_reader_listening,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
        if (reader.progressPct != null) {
            // The bar replaces the number (spec §5.2); the number is spoken, merged into the row.
            val spoken = stringResource(Res.string.book_detail_readers_a11y_reading, reader.name, reader.progressPct)
            LinearProgressIndicator(
                progress = { reader.progressPct.coerceIn(0, 100) / 100f },
                modifier =
                    Modifier
                        .padding(top = 6.dp)
                        .widthIn(max = 150.dp)
                        .fillMaxWidth()
                        .semantics { contentDescription = spoken },
            )
        }
    } else if (reader.finishedWhen != null && reader.isOnHardcover) {
        // Flows rather than a Row: at a large font scale the label wraps beneath the date instead of
        // crushing it.
        // One phrase for TalkBack — "Read May 2016 on Hardcover" — where the eye reads the date beside the label.
        val spoken = stringResource(Res.string.book_detail_readers_read_on_hardcover, reader.finishedWhen)
        FlowRow(
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { contentDescription = spoken },
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.book_detail_readers_read, reader.finishedWhen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalLabel(label = stringResource(Res.string.book_detail_readers_hardcover))
        }
    } else if (reader.finishedWhen != null && reader.isAlsoOnHardcover) {
        // One phrase for TalkBack, merged into the row's name — "Ada, Finished Oct 1, also on
        // Hardcover" — where the eye reads the date beside the label.
        val spoken = stringResource(Res.string.book_detail_readers_finished_also_on_hardcover, reader.finishedWhen)
        FlowRow(
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { contentDescription = spoken },
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.book_detail_readers_finished, reader.finishedWhen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalLabel(label = stringResource(Res.string.book_detail_readers_also_on_hardcover))
        }
    } else if (reader.finishedWhen != null) {
        Text(
            text = stringResource(Res.string.book_detail_readers_finished, reader.finishedWhen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    } else if (reader.isRatedOnly) {
        Text(
            text = stringResource(Res.string.book_detail_readers_rated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** "1 reader" or "N readers": what the Readers count badge counts. */
@Composable
private fun readerCount(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.book_detail_readers_count_one)
    } else {
        stringResource(Res.string.book_detail_readers_count, count)
    }

/**
 * The readers as the page needs them: whether they have arrived (a landing on Readers waits for them, so the section
 * it scrolls to has its height) and the summary for the hero's social line — null while loading, on error, or when
 * nobody else reads it. It resolves the same [BookReadersViewModel] as [BookReadersSection]: Koin keys a ViewModel by
 * its class within the entry's ViewModelStoreOwner, so the two share one repository flow.
 *
 * @property hasArrived The readers have loaded, with someone to show.
 * @property summary Who else is reading, for the social line.
 */
internal data class PageReaders(
    val hasArrived: Boolean,
    val summary: ReadersSummary?,
)

/** The page's readers; see [PageReaders]. */
@Composable
internal fun rememberPageReaders(
    bookId: String,
    viewModel: BookReadersViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
): PageReaders {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val data = state as? BookReadersUiState.Data ?: return PageReaders(hasArrived = false, summary = null)
    return PageReaders(hasArrived = true, summary = data.summary.takeIf { it.avatars.isNotEmpty() })
}
