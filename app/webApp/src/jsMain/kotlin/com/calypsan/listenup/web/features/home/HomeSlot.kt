package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.web.dom.Button as DomButton

private const val HERO_COVER_RUNG = 240
private const val ONLY_BOOK_COVER = 170
private const val HERO_COVER = 112
private const val NEXT_COVER = 40
private const val PLAY_ICON_SIZE = 18

/**
 * The Resume kind (spec §2.3): nothing is loaded and this is the book last played. The cover and title are one button
 * into the book (H15, one tab stop); Resume is a separate primary button that plays from the saved position (C18).
 * The surface is Book Detail's neutral hero wash until the cover wash lands (spec D3). The author is plain text and
 * there is no series line: the shared state carries neither a series nor an author link (open question 4).
 */
@Composable
internal fun ResumeHero(
    resume: HomeSlot.Resume,
    isOnlyBook: Boolean,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    val book = resume.book
    Section(attrs = {
        classes("home-hero")
        if (isOnlyBook) classes("is-only")
        attr("aria-label", "Up next")
    }) {
        DomButton(attrs = {
            classes("home-hero-id")
            attr("type", "button")
            onClick { onOpenBook(book.bookId) }
        }) {
            Cover(
                title = book.title,
                imageUrl = coverUrl(book.bookId, book.coverHash, width = HERO_COVER_RUNG),
                size = if (isOnlyBook) ONLY_BOOK_COVER else HERO_COVER,
                decorative = true,
                overlay = { RestrictedMarker(book.bookId) },
            )
            Span(attrs = { classes("home-hero-text") }) {
                Span(attrs = { classes("home-hero-eyebrow") }) {
                    Text("Up next · ${relativeLastActiveInSentence(book.lastPlayedAtMs, nowMs)}")
                }
                Span(attrs = { classes("home-hero-t") }) { Text(book.title) }
                if (book.authorNames.isNotBlank()) Span(attrs = { classes("home-hero-a") }) { Text(book.authorNames) }
                book.narratorNames?.takeIf { it.isNotBlank() }?.let { names ->
                    Span(attrs = { classes("home-hero-n") }) { Text("Read by $names") }
                }
            }
        }
        Div(attrs = { classes("home-hero-progress") }) {
            Div(attrs = { classes("home-hero-line") }) {
                Span { Text(resume.chapter?.let { "Ch. ${it.number} · ${it.title}" }.orEmpty()) }
                Span(attrs = { classes("home-hero-left") }) { Text(timeLeftText(book.timeRemainingMs)) }
            }
            ProgressBar(value = book.progress, label = "Listening progress", look = ProgressLook.Bar)
            Span(attrs = { classes("home-hero-of") }) {
                Text("${book.progressPercent}% of ${DurationFormatter.hoursMinutes(book.totalDurationMs.milliseconds)}")
            }
        }
        resume.next?.let { next -> NextInSeriesButton(next, onOpenBook) }
        Button(
            kind = ButtonKind.Primary,
            size = ButtonSize.Lg,
            fill = true,
            enabled = resume.canPlay,
            onClick = { onPlay(book.bookId) },
            attrs = { classes("home-hero-resume") },
        ) {
            Icon(WebIcon.Play, size = PLAY_ICON_SIZE)
            Text("Resume")
        }
        if (!resume.canPlay) {
            P(attrs = { classes("home-hero-why") }) {
                Text("Can't reach your server, so this book can't stream right now.")
            }
        }
    }
}

/** "Next in the series" (§2.3, shared by the Resume and finish cards): a quiet row that opens that book. */
@Composable
internal fun NextInSeriesButton(
    next: NextInSeries,
    onOpenBook: (String) -> Unit,
) {
    DomButton(attrs = {
        classes("home-hero-next")
        attr("type", "button")
        onClick { onOpenBook(next.bookId) }
    }) {
        Cover(
            title = next.title,
            imageUrl = coverUrl(next.bookId, next.coverHash, width = NEXT_COVER * 2),
            size = NEXT_COVER,
            decorative = true,
            overlay = { RestrictedMarker(next.bookId, compact = true) },
        )
        Span(attrs = { classes("home-hero-next-text") }) {
            Span(attrs = { classes("home-hero-next-k") }) { Text("Next in the series") }
            Span(attrs = { classes("home-hero-next-t") }) { Text(next.title) }
        }
    }
}
