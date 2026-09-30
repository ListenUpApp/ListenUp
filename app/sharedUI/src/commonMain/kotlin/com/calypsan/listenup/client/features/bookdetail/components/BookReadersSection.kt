@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.util.relativeOrMonthYear
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.book_detail_readers_finished
import listenup.composeapp.generated.resources.book_detail_progresspercent
import listenup.composeapp.generated.resources.book_detail_readers_listening_now
import listenup.composeapp.generated.resources.book_detail_readers_note
import listenup.composeapp.generated.resources.book_detail_readers_hardcover
import listenup.composeapp.generated.resources.book_detail_readers_rated
import listenup.composeapp.generated.resources.book_detail_readers_read
import listenup.composeapp.generated.resources.common_see_all
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

private const val MAX_COLLAPSED_READERS = 5

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
)

/**
 * The row's identity in a lazy list. A ListenUp finish and a Hardcover read shown with the same date
 * are two rows, so where the read was logged is part of the key — a duplicate key crashes the list.
 */
internal val ReaderRowUi.listKey: String
    get() = "$userId:$isReading:$isOnHardcover:$finishedWhen"

/**
 * Flattens readers into [ReaderRowUi] rows for both the capped Book Detail section and the full
 * [com.calypsan.listenup.client.features.bookreaders.BookReadersScreen]. Reading lines come first,
 * then finished lines newest-first (see [flattenToLines]); the caller's own row is labelled "You".
 *
 * @param nowMs Current epoch-ms reference, passed to [relativeOrMonthYear] for finished dates.
 */
internal fun List<Reader>.toReaderRows(nowMs: Long): List<ReaderRowUi> =
    flattenToLines(this).map { line ->
        val name = if (line.isYou) "You" else line.name
        when (val k = line.kind) {
            is ReaderLineKind.Reading -> {
                ReaderRowUi(
                    userId = line.userId,
                    name = name,
                    isReading = true,
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
 * @param viewModel The ViewModel for loading readers data; scoped to [bookId].
 */
@Composable
fun BookReadersSection(
    bookId: String,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isCard: Boolean = false,
    onSeeAllClick: (String) -> Unit = {},
    viewModel: BookReadersViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Loading and Error render nothing — the Readers section is non-critical.
    val data = state as? BookReadersUiState.Data ?: return

    val nowMs = Clock.System.now().toEpochMilliseconds()
    val allRows = data.readers.readers.toReaderRows(nowMs)
    val rows = allRows.take(MAX_COLLAPSED_READERS)

    BookReadersContent(
        readers = rows,
        listeningNowCount = allRows.count { it.isReading },
        totalCount = allRows.size,
        isCard = isCard,
        onUserClick = onUserClick,
        onSeeAllClick = { onSeeAllClick(bookId) },
        modifier = modifier,
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
 * @param listeningNowCount Count shown in the "N listening now" sub-line (readers currently
 *   reading).
 * @param totalCount Total reader count shown in the [CountBadge]; gates the "See all" affordance.
 * @param isCard When true, wraps the content in a `surfaceContainerLow` card.
 * @param onUserClick Callback when a reader row is clicked.
 * @param onSeeAllClick Callback for the "See all" affordance, shown only when [totalCount] exceeds
 *   the number of rendered rows.
 * @param modifier Optional modifier.
 */
@Composable
fun BookReadersContent(
    readers: List<ReaderRowUi>,
    listeningNowCount: Int,
    totalCount: Int,
    isCard: Boolean,
    onUserClick: (String) -> Unit,
    onSeeAllClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    if (readers.isEmpty()) return

    val innerPadding = if (isCard) Spacing.screenMargin else 0.dp

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding)) {
            // Header row: title + CountBadge + spacer + "See all"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.book_detail_readers),
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.width(8.dp))

                CountBadge(count = totalCount)

                Spacer(modifier = Modifier.weight(1f))

                if (totalCount > readers.size) {
                    TextButton(
                        onClick = {
                            haptics.press()
                            onSeeAllClick()
                        },
                    ) {
                        Text(
                            text = stringResource(Res.string.common_see_all),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Sub-line: "N listening now" — only when someone is actively listening
            if (listeningNowCount > 0) {
                Text(
                    text = stringResource(Res.string.book_detail_readers_listening_now, listeningNowCount),
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
                .clickable { onUserClick(reader.userId) }
                .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Active ring: a 2.5dp primary border offset 2dp from the avatar when reading.
        val avatarModifier =
            if (reader.isReading) {
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

        UserAvatar(
            userId = reader.userId,
            size = AvatarSize.Medium,
            modifier = avatarModifier,
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

            if (reader.isReading) {
                if (reader.progressPct != null) {
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProgressBar(
                            progressPct = reader.progressPct,
                            modifier = Modifier.widthIn(max = 150.dp).weight(1f, fill = false),
                        )
                        Text(
                            text = stringResource(Res.string.book_detail_progresspercent, reader.progressPct),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else if (reader.finishedWhen != null && reader.isOnHardcover) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.book_detail_readers_read, reader.finishedWhen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TonalLabel(label = stringResource(Res.string.book_detail_readers_hardcover))
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

        if (reader.isReading) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else if (reader.isRatedOnly) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Thin progress bar: a 6dp-tall [MaterialTheme.colorScheme.surfaceContainerHighest] track with a
 * [MaterialTheme.colorScheme.primary] fill to [progressPct]%.
 *
 * @param progressPct Fill amount in `0..100`.
 * @param modifier Optional modifier; callers cap the width.
 */
@Composable
private fun ProgressBar(
    progressPct: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progressPct.coerceIn(0, 100) / 100f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}
