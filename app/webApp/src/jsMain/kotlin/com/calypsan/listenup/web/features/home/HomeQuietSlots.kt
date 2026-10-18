package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.web.dom.Button as DomButton

private const val CANDIDATE_COVER = 96
private const val WELCOME_COVER = 72
private const val COVER_RUNG = 192
private const val WELCOME_COVERS = 4

/**
 * Nothing in progress, but the reader has listened before (spec §2.3): with a series candidate, its card with **Play**
 * and **Browse the Library**; without one, Browse the Library and Discover (H17).
 */
@Composable
internal fun NothingInProgressSlot(
    slot: HomeSlot.NothingInProgress,
    actions: HomeActions,
) {
    Section(attrs = {
        classes("home-section", "home-quiet")
        attr("aria-labelledby", "home-quiet-h")
    }) {
        H2(attrs = {
            id("home-quiet-h")
            classes("home-section-h")
        }) { Text(HomeWords.NOTHING_IN_PROGRESS) }
        val candidate = slot.candidate
        if (candidate == null) {
            Div(attrs = { classes("home-quiet-actions") }) {
                SecondaryButton(HomeWords.BROWSE_LIBRARY, actions.onOpenLibrary)
                SecondaryButton(HomeWords.DISCOVER, actions.onOpenDiscover)
            }
        } else {
            Div(attrs = { classes("home-hero") }) {
                CandidateIdentity(candidate, actions.onOpenBook)
                Div(attrs = { classes("home-quiet-actions") }) {
                    Button(
                        kind = ButtonKind.Primary,
                        size = ButtonSize.Lg,
                        onClick = { actions.onPlay(candidate.bookId) },
                        attrs = { classes("home-hero-resume") },
                    ) { Text(HomeWords.PLAY) }
                    SecondaryButton(HomeWords.BROWSE_LIBRARY, actions.onOpenLibrary)
                }
            }
        }
    }
}

/** Cover, "Next in a series you're reading", title, narrators and the series line: one control into the book. */
@Composable
private fun CandidateIdentity(
    next: NextInSeries,
    onOpenBook: (String) -> Unit,
) {
    DomButton(attrs = {
        classes("home-hero-id")
        attr("type", "button")
        onClick { onOpenBook(next.bookId) }
    }) {
        Cover(
            title = next.title,
            imageUrl = coverUrl(next.bookId, next.coverHash, width = COVER_RUNG),
            size = CANDIDATE_COVER,
            decorative = true,
            overlay = { RestrictedMarker(next.bookId) },
        )
        Span(attrs = { classes("home-hero-text") }) {
            Span(attrs = { classes("home-hero-eyebrow") }) { Text(HomeWords.NEXT_IN_A_SERIES) }
            Span(attrs = { classes("home-hero-t") }) { Text(next.title) }
            next.narratorNames?.takeIf { it.isNotBlank() }?.let { names ->
                Span(attrs = { classes("home-hero-n") }) { Text(HomeWords.readBy(names)) }
            }
            Span(attrs = { classes("home-hero-n") }) {
                Text(
                    HomeWords.seriesLine(
                        series = next.seriesName,
                        sequence = next.sequenceLabel,
                        length = DurationFormatter.hoursMinutes(next.durationMs.milliseconds),
                    ),
                )
            }
        }
    }
}

/**
 * No listening history at all (spec §2.3 NewListener): four of the newest covers, "Pick something new", and the way
 * in — Browse the Library (primary) and Discover. Never a count of the library (Simon, 2026-10-14).
 */
@Composable
internal fun WelcomeSlot(
    slot: HomeSlot.NewListener,
    actions: HomeActions,
) {
    Section(attrs = {
        classes("home-hero", "home-welcome")
        attr("aria-label", HomeWords.GETTING_STARTED)
    }) {
        Div(attrs = {
            classes("home-welcome-covers")
            attr("aria-hidden", "true")
        }) {
            val covers = slot.covers.take(WELCOME_COVERS)
            covers.forEach { cover ->
                key(cover.bookId) {
                    Cover(
                        title = "",
                        imageUrl = coverUrl(cover.bookId, cover.coverHash, width = COVER_RUNG),
                        size = WELCOME_COVER,
                        decorative = true,
                        overlay = { RestrictedMarker(cover.bookId, compact = true) },
                    )
                }
            }
            repeat(WELCOME_COVERS - covers.size) { Div(attrs = { classes("skel", "home-welcome-blank") }) }
        }
        Span(attrs = { classes("home-hero-t") }) { Text(HomeWords.WELCOME_HEADLINE) }
        P(attrs = { classes("home-hero-why") }) { Text(HomeWords.WELCOME_BODY) }
        Div(attrs = { classes("home-quiet-actions") }) {
            Button(
                kind = ButtonKind.Primary,
                size = ButtonSize.Lg,
                onClick = { actions.onOpenLibrary() },
                attrs = { classes("home-hero-resume") },
            ) { Text(HomeWords.BROWSE_LIBRARY) }
            SecondaryButton(HomeWords.DISCOVER, actions.onOpenDiscover)
        }
    }
}

/** The quiet slots' outlined pill (spec §2.4). */
@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(kind = ButtonKind.Secondary, size = ButtonSize.Lg, onClick = { onClick() }) { Text(text) }
}
