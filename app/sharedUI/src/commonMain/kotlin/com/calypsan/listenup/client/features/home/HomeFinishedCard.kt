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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.home.AlsoFinished
import com.calypsan.listenup.client.presentation.home.FinishedCard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_also_finished_one
import listenup.composeapp.generated.resources.home_also_finished_one_more
import listenup.composeapp.generated.resources.home_also_finished_two
import listenup.composeapp.generated.resources.home_also_finished_two_more
import listenup.composeapp.generated.resources.home_date_range
import listenup.composeapp.generated.resources.home_dismiss_everywhere_a11y
import listenup.composeapp.generated.resources.home_finished_days_ago
import listenup.composeapp.generated.resources.home_finished_line_no_range
import listenup.composeapp.generated.resources.home_finished_today
import listenup.composeapp.generated.resources.home_finished_yesterday
import listenup.composeapp.generated.resources.home_how_was_it
import listenup.composeapp.generated.resources.home_play_next_book
import listenup.composeapp.generated.resources.home_play_next_book_a11y
import listenup.composeapp.generated.resources.home_you_finished_a_book_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Test tag for the finish card's one link into the finished book (H15). */
internal const val HOME_FINISHED_IDENTITY_TAG = "home-finished-identity"

private val FinishedCover = 120.dp
private val PlayNextHeight = 56.dp

/**
 * The just-finished kind (spec §2.3): when it was finished, the book as one link, the next in its series with
 * **Play next book** (the leading action, C16), the shared half-star control (Q2) and who else finished it. ✕ dismisses
 * it on every device. No next book: no primary button, and the rating is the action.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeFinishedCard(
    card: FinishedCard,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val groupLabel = stringResource(Res.string.home_you_finished_a_book_a11y)
    Card(
        modifier =
            modifier.fillMaxWidth().semantics {
                isTraversalGroup = true
                contentDescription = groupLabel
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = finishedEyebrow(finishedDaysAgo(card.finishedAtMs, nowMs, zone)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.onDismissFinished(card.book.bookId) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.home_dismiss_everywhere_a11y),
                    )
                }
            }
            FinishedIdentity(card, zone, onOpen = { actions.onOpenBook(card.book.bookId) })
            card.next?.let { next ->
                NextInSeriesRow(next, onOpen = { actions.onOpenBook(next.bookId) })
                val playLabel = stringResource(Res.string.home_play_next_book_a11y, next.title)
                Button(
                    onClick = { actions.onPlay(next.bookId) },
                    shapes = ButtonDefaults.shapes(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = PlayNextHeight)
                            .semantics { contentDescription = playLabel },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.home_play_next_book))
                }
            }
            FinishedRating(card, onRate = { actions.onRateFinished(card.book.bookId, it) })
            alsoFinishedText(card.alsoFinished)?.let { line ->
                TextButton(onClick = { actions.onOpenReaders(card.book.bookId) }) {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Cover, title, author and "45h 30m · 6 Sep – 10 Oct": one link, one tab stop (b1). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FinishedIdentity(
    card: FinishedCard,
    zone: TimeZone,
    onOpen: () -> Unit,
) {
    val book = card.book
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onOpen)
            .testTag(HOME_FINISHED_IDENTITY_TAG),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BookCoverImage(
                bookId = book.bookId,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = null,
                title = book.title,
                author = book.authorNames,
                // Decorative: the link's text names the book.
                modifier = Modifier.size(FinishedCover).clip(ContentShapes.card).clearAndSetSemantics {},
            )
            RestrictedBookMarker(book.bookId, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
        }
        Column {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.authorNames.isNotBlank()) {
                Text(book.authorNames, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text =
                    finishedMeta(
                        durationMs = book.durationMs,
                        startedAtMs = card.startedAtMs,
                        finishedAtMs = card.finishedAtMs,
                        zone = zone,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "How was it?" over the shared half-star control; a drag previews, a settle rates. */
@Composable
private fun FinishedRating(
    card: FinishedCard,
    onRate: (Int) -> Unit,
) {
    var preview by remember(card.book.bookId, card.yourHalfStars) { mutableIntStateOf(card.yourHalfStars ?: 0) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = stringResource(Res.string.home_how_was_it),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        RatingStars(
            halfStars = preview,
            onHalfStarsChange = { preview = it },
            onHalfStarsCommit = onRate,
        )
    }
}

@Composable
private fun finishedEyebrow(daysAgo: Int): String =
    when (daysAgo) {
        0 -> stringResource(Res.string.home_finished_today)
        1 -> stringResource(Res.string.home_finished_yesterday)
        else -> stringResource(Res.string.home_finished_days_ago, daysAgo)
    }

@Composable
private fun finishedMeta(
    durationMs: Long,
    startedAtMs: Long?,
    finishedAtMs: Long,
    zone: TimeZone,
): String {
    val length = DurationFormatter.hoursMinutes(durationMs.milliseconds)
    // Q8: a finish with no known start (imported from history) shows its length alone.
    startedAtMs ?: return length
    val range =
        stringResource(Res.string.home_date_range, shortDayMonth(startedAtMs, zone), shortDayMonth(finishedAtMs, zone))
    return stringResource(Res.string.home_finished_line_no_range, length, range)
}

/** "Maya, Tom and 3 more finished it too" (C9), or null when nobody is named (Q7). */
@Composable
private fun alsoFinishedText(also: AlsoFinished): String? {
    val names = also.names.filter { it.isNotBlank() }
    val more = also.othersCount
    return when {
        names.isEmpty() -> null
        names.size == 1 && more == 0 -> stringResource(Res.string.home_also_finished_one, names[0])
        names.size == 1 -> stringResource(Res.string.home_also_finished_one_more, names[0], more)
        more == 0 -> stringResource(Res.string.home_also_finished_two, names[0], names[1])
        else -> stringResource(Res.string.home_also_finished_two_more, names[0], names[1], more)
    }
}

/** Local calendar days from [finishedAtMs] to [nowMs] in [zone]: 0 today, 1 yesterday (C10). */
internal fun finishedDaysAgo(
    finishedAtMs: Long,
    nowMs: Long,
    zone: TimeZone,
): Int = localDate(finishedAtMs, zone).daysUntil(localDate(nowMs, zone)).coerceAtLeast(0)

/** "6 Sep": the day, then the short English month, in [zone]. */
internal fun shortDayMonth(
    epochMs: Long,
    zone: TimeZone,
): String {
    val date = localDate(epochMs, zone)
    return "${date.day} ${SHORT_MONTHS[date.month.number - 1]}"
}

private fun localDate(
    epochMs: Long,
    zone: TimeZone,
) = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
