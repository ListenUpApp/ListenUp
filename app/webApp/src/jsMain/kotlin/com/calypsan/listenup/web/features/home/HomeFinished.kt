package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.home.FinishedCard
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.jetbrains.compose.web.dom.Button as DomButton
import com.calypsan.listenup.web.design.coverThemed
import com.calypsan.listenup.web.design.rememberCoverThemes

private const val FINISHED_COVER = 112
private const val FINISHED_COVER_RUNG = 240
private const val ICON_SIZE = 18

/**
 * The just-finished kind (spec §2.3): a region named "You finished a book" on the hero wash (C17). When it was
 * finished, the book as one control, the next in its series with **Play next book** (the leading action, C16), the
 * shared half-star control (Simon's decision, Q2) and who else finished it. ✕ dismisses it on every device. With no
 * next book there is no primary button, and the rating is the action.
 */
@Composable
internal fun HomeFinishedCard(
    card: FinishedCard,
    nowMs: Long,
    actions: HomeActions,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val book = card.book
    // The cover's wash, or Book Detail's neutral tone when the cover gives none (Home spec D3).
    val coverTheme = rememberCoverThemes(book.bookId, book.coverHash)
    Section(attrs = {
        classes("home-hero")
        coverThemed(coverTheme)
        attr("aria-label", HomeWords.YOU_FINISHED_A_BOOK)
    }) {
        Div(attrs = { classes("home-finished-top") }) {
            Span(attrs = { classes("home-hero-eyebrow") }) {
                Text(HomeWords.finished(finishedDaysAgo(card.finishedAtMs, nowMs, zone)))
            }
            Button(
                kind = ButtonKind.Icon,
                label = HomeWords.DISMISS_EVERYWHERE,
                onClick = { actions.onDismissFinished(book.bookId) },
            ) { Icon(WebIcon.X, size = ICON_SIZE) }
        }
        DomButton(attrs = {
            classes("home-hero-id", "home-finished-id")
            attr("type", "button")
            onClick { actions.onOpenBook(book.bookId) }
        }) {
            Cover(
                title = book.title,
                imageUrl = coverUrl(book.bookId, book.coverHash, width = FINISHED_COVER_RUNG),
                size = FINISHED_COVER,
                decorative = true,
                overlay = { RestrictedMarker(book.bookId) },
            )
            Span(attrs = { classes("home-hero-text") }) {
                Span(attrs = { classes("home-hero-t") }) { Text(book.title) }
                if (book.authorNames.isNotBlank()) Span(attrs = { classes("home-hero-a") }) { Text(book.authorNames) }
                Span(attrs = { classes("home-hero-n") }) {
                    Text(
                        HomeWords.finishedMeta(
                            length = DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
                            startedOn = card.startedAtMs?.let { shortDayMonth(it, zone) },
                            finishedOn = shortDayMonth(card.finishedAtMs, zone),
                        ),
                    )
                }
            }
        }
        card.next?.let { next ->
            NextInSeriesButton(next, actions.onOpenBook)
            Button(
                kind = ButtonKind.Primary,
                size = ButtonSize.Lg,
                fill = true,
                label = HomeWords.playNextBook(next.title),
                onClick = { actions.onPlay(next.bookId) },
                attrs = { classes("home-hero-resume") },
            ) {
                Icon(WebIcon.Play, size = ICON_SIZE)
                Text(HomeWords.PLAY_NEXT_BOOK)
            }
        }
        FinishedRating(
            bookId = book.bookId,
            yourHalfStars = card.yourHalfStars,
            onRate = { actions.onRateFinished(book.bookId, it) },
        )
        HomeWords.alsoFinished(card.alsoFinished.names, card.alsoFinished.othersCount)?.let { line ->
            Button(
                kind = ButtonKind.Ghost,
                onClick = { actions.onOpenReaders(book.bookId) },
                attrs = { classes("home-finished-others") },
            ) { Text(line) }
        }
    }
}

/**
 * "How was it?" over the shared half-star control; a drag previews, a settle rates. Home's finished card and Now
 * Playing's share it (C22): both describe the same finished book.
 */
@Composable
internal fun FinishedRating(
    bookId: String,
    yourHalfStars: Int?,
    onRate: (Int) -> Unit,
) {
    var preview by remember(bookId, yourHalfStars) { mutableIntStateOf(yourHalfStars ?: 0) }
    Div(attrs = { classes("home-finished-rate") }) {
        Span(attrs = {
            classes("home-finished-rate-k")
            attr("aria-hidden", "true")
        }) { Text(HomeWords.HOW_WAS_IT) }
        RatingStars(
            halfStars = preview,
            onHalfStarsChange = { preview = it },
            onHalfStarsCommit = onRate,
            label = HomeWords.HOW_WAS_IT,
        )
    }
}

/** Local calendar days from [finishedAtMs] to [nowMs] in [zone]: 0 today, 1 yesterday (C10). */
internal fun finishedDaysAgo(
    finishedAtMs: Long,
    nowMs: Long,
    zone: TimeZone,
): Int = localDate(finishedAtMs, zone).daysUntil(localDate(nowMs, zone)).coerceAtLeast(0)

/** "6 Sep": the day, then the short month, in [zone]. */
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
