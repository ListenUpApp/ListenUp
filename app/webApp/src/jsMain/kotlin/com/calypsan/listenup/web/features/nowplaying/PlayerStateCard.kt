package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCardTone
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.home.FinishedRating
import com.calypsan.listenup.web.features.home.HomeWords
import com.calypsan.listenup.web.features.home.finishedDaysAgo
import com.calypsan.listenup.web.features.home.shortDayMonth
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The card that takes the transport's place on Now Playing (P32, §5.6's web column): a problem with the ways out the
 * shared [com.calypsan.listenup.client.presentation.player.PlayerCards] decided, the finished book, or the sleep
 * timer's fade. A problem is `role=alert`, so its arrival is announced; the fade is a polite `role=status`. The finished card reuses Home's finished words and rating (C22).
 */
@Composable
internal fun PlayerStateCard(
    card: PlayerCard,
    onAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    bookId: String = "",
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    when (card) {
        is PlayerCard.Problem -> ProblemCard(card, onAction)
        is PlayerCard.Finished -> FinishedBookCard(card, bookId, onAction, onRate, nowMs, zone)
        PlayerCard.SleepFading -> SleepFadingCard(onAction)
    }
}

/**
 * The sleep timer's fade (§5.6 "Sleep fading", `States-WebPhone`): the sleep gold with the moon, "Fading out…", what
 * is happening, and one button, Keep listening. `role=status`, polite: nothing failed.
 */
@Composable
private fun SleepFadingCard(onAction: (PlayerCardAction) -> Unit) {
    Div(attrs = {
        classes("np-card", "sleep")
        attr("role", "status")
    }) {
        P(attrs = { classes("np-card-t") }) {
            Icon(WebIcon.Moon, size = MOON_ICON_SIZE)
            Text(NowPlayingWords.FADING_TITLE)
        }
        P(attrs = { classes("np-card-c") }) { Text(NowPlayingWords.FADING_BODY) }
        Div(attrs = { classes("np-card-actions") }) {
            Button(
                kind = ButtonKind.Primary,
                size = ButtonSize.Lg,
                onClick = { onAction(PlayerCardAction.KeepListening) },
                attrs = { classes("np-keep") },
            ) { Text(NowPlayingWords.action(PlayerCardAction.KeepListening)) }
        }
    }
}

@Composable
private fun ProblemCard(
    card: PlayerCard.Problem,
    onAction: (PlayerCardAction) -> Unit,
) {
    val error = card.error
    Div(attrs = {
        classes("np-card", card.tone.cssClass())
        attr("role", "alert")
    }) {
        P(attrs = { classes("np-card-t") }) { Text(error.playerTitle()) }
        error.playerCause()?.let { cause -> P(attrs = { classes("np-card-c") }) { Text(cause) } }
        if (card.placeSaved) P(attrs = { classes("np-card-saved") }) { Text(NowPlayingWords.PLACE_SAVED) }
        error.correlationId?.let { id ->
            P(attrs = { classes("np-card-ref", "mono") }) { Text(NowPlayingWords.reference(id)) }
        }
        CardActions(card.primaryAction, card.secondaryAction, onAction)
    }
}

@Composable
private fun FinishedBookCard(
    card: PlayerCard.Finished,
    bookId: String,
    onAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    nowMs: Long,
    zone: TimeZone,
) {
    val info = card.info
    Div(attrs = { classes("np-card", "finished") }) {
        info.finishedAtMs?.let { finishedAt ->
            P(attrs = { classes("np-card-t") }) { Text(HomeWords.finished(finishedDaysAgo(finishedAt, nowMs, zone))) }
            P(attrs = { classes("np-card-c") }) { Text(finishedMeta(info, finishedAt, zone)) }
        }
        FinishedRating(bookId = bookId, yourHalfStars = info.yourHalfStars, onRate = onRate)
        info.nextBook?.let { NextBook(it) }
        CardActions(card.primaryAction, card.secondaryAction, onAction)
    }
}

/** "45h 30m · 6 Sep – 10 Oct": Home's line for the same finished book. */
private fun finishedMeta(
    info: FinishedInfo,
    finishedAtMs: Long,
    zone: TimeZone,
): String =
    HomeWords.finishedMeta(
        length = DurationFormatter.hoursMinutes(info.durationMs.milliseconds),
        startedOn = info.startedAtMs?.let { shortDayMonth(it, zone) },
        finishedOn = shortDayMonth(finishedAtMs, zone),
    )

/** The next book in the series: its cover, its title and its place. Play next book is the card's button. */
@Composable
private fun NextBook(next: NextInSeries) {
    Div(attrs = { classes("np-card-next") }) {
        Cover(
            title = next.title,
            imageUrl = coverUrl(next.bookId, next.coverHash, width = NEXT_COVER * 2),
            size = NEXT_COVER,
            decorative = true,
        )
        Span(attrs = { classes("np-card-next-text") }) {
            Span(attrs = { classes("np-card-next-k") }) { Text(NowPlayingWords.NEXT_IN_SERIES) }
            Span(attrs = { classes("np-card-next-t") }) { Text(next.title) }
            next.sequenceLabel?.let {
                Span(
                    attrs = { classes("np-card-next-n") },
                ) { Text(NowPlayingWords.nextBook(it)) }
            }
        }
    }
}

/** The card's one or two ways out: the first filled, the second outlined; Tap to play is the ringed play circle. */
@Composable
private fun CardActions(
    primary: PlayerCardAction,
    secondary: PlayerCardAction?,
    onAction: (PlayerCardAction) -> Unit,
) {
    Div(attrs = { classes("np-card-actions") }) {
        if (primary == PlayerCardAction.TapToPlay) {
            DomButton(attrs = {
                classes("np-play", "ring")
                attr("type", "button")
                attr("aria-label", NowPlayingWords.action(primary))
                attr("title", NowPlayingWords.action(primary))
                onClick { onAction(primary) }
            }) { Icon(WebIcon.Play, size = PLAY_ICON_SIZE) }
        } else {
            Button(kind = ButtonKind.Primary, size = ButtonSize.Lg, onClick = { onAction(primary) }) {
                Text(NowPlayingWords.action(primary))
            }
        }
        secondary?.let { action ->
            Button(kind = ButtonKind.Secondary, size = ButtonSize.Lg, onClick = { onAction(action) }) {
                Text(NowPlayingWords.action(action))
            }
        }
    }
}

private fun PlayerCardTone.cssClass(): String =
    when (this) {
        PlayerCardTone.Error -> "error"
        PlayerCardTone.Held -> "held"
        PlayerCardTone.Neutral -> "neutral"
    }

private const val NEXT_COVER = 56

private const val PLAY_ICON_SIZE = 32

private const val MOON_ICON_SIZE = 20
