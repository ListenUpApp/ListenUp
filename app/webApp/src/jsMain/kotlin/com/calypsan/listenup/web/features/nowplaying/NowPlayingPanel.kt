package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.isSameVolumeBoost
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Button as DsButton
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The expanded player — what is playing, in full, over the same session the docked bar reads.
 *
 * The bar is a strip: it can hold a title, a playhead and a row of controls, and that is the
 * ceiling. This is where the rest of the book lives — its cover, who wrote it, which series it
 * belongs to, which chapter is playing — and where the things you do *about* the book rather than
 * *to the playhead* live: going to it, to its series, to its author.
 *
 * A modal [PlayerDialog] rather than a `/player` route, matching the four panels that were already
 * player surfaces on web. That buys the focus trap, the inert page and Escape-to-close from the
 * platform instead of reimplementing them, and a `/player` URL would be the one route in this app
 * that shows its recipient something different from what the sender saw — their session, not this
 * one. The URL-is-a-contract rule is about addressing *content*; a player is not content.
 *
 * Previous and Next act through the shared model, so §3.3's rule holds here as on every platform: Previous
 * restarts the chapter after its first three seconds, and in chapter one restarts it; Next is unavailable
 * in the last chapter.
 *
 * The four session controls open their existing pickers ON TOP of this panel rather than replacing
 * it. Stacked `<dialog>`s are exactly what the top layer is for: the picker takes focus, Escape
 * dismisses just the picker, and the panel the listener opened is still there underneath when it
 * closes. Closing this panel to change the speed and landing them back on the page would lose the
 * place they were looking at.
 */
@Composable
internal fun NowPlayingPanel(
    open: Boolean,
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    onPrimary: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenBoost: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenContributor: (String) -> Unit,
    onCloseBook: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    val book = state.book
    val settings = state.settings
    PlayerDialog(open = open, title = "Now playing", panelClass = "np-dlg", onDismiss = onDismiss) {
        // Above the book, so the cause is the first thing read; PR 3's error card takes its place.
        PlayerProblemNote(error = problemErrorOf(state), primary = state.primary, onRetry = onRetry, onDismiss = onDismissError)
        Div(attrs = { classes("np-body") }) {
            Cover(
                title = book.title,
                imageUrl = coverUrl(book.id, book.coverHash, COVER_RUNG),
                size = COVER_SIZE,
                radius = COVER_RADIUS,
                decorative = true,
                eager = true,
            )
            Div(attrs = { classes("np-meta") }) {
                Div(attrs = { classes("np-t") }) { Text(book.title) }
                Byline(book.authors, onOpenContributor)
                SeriesLine(book.series, onOpenSeries)
                ChapterLine(state.chapter)
            }
        }

        Div(attrs = { classes("np-transport") }) {
            ChapterStep(
                icon = WebIcon.ChevronLeft,
                label = "Previous chapter",
                enabled = !state.chapter.isWholeBook,
                onClick = onPreviousChapter,
            )
            if (state.showsSkipBack) {
                PanelButton(WebIcon.SkipBack, "Back ${settings.skipBackSec} seconds", onSkipBack)
            }
            val label = primaryLabel(state.primary, state.phase)
            Button(attrs = {
                classes("np-play")
                if (state.primary == PrimaryControl.TapToPlay) classes("ring")
                attr("type", TYPE_BUTTON)
                attr("aria-label", label)
                onClick { onPrimary() }
            }) {
                if (state.primary == PrimaryControl.Busy) {
                    Div(attrs = {
                        classes("tport-wait")
                        attr("aria-hidden", "true")
                    })
                } else {
                    Icon(primaryIcon(state.primary), size = PLAY_ICON_SIZE)
                }
            }
            PanelButton(WebIcon.SkipForward, "Forward ${settings.skipForwardSec} seconds", onSkipForward)
            ChapterStep(
                icon = WebIcon.ChevronRight,
                label = "Next chapter",
                enabled = state.canSkipToNextChapter,
                onClick = onNextChapter,
            )
        }

        Div(attrs = { classes("np-scrub-row") }) {
            Playhead(
                positionMs = progress.bookPositionMs,
                durationMs = progress.bookDurationMs,
                timeClass = "np-time",
                scrubClass = "np-scrub",
                onSeek = onSeek,
            )
        }

        Div(attrs = { classes("np-actions") }) {
            ActionChip(
                "${formatSpeed(settings.speed)}×",
                settings.speed != PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
                onOpenSpeed,
            )
            if (state.chapters.isNotEmpty()) ActionChip("Chapters", false, onOpenChapters)
            ActionChip(
                label = boostLabel(settings.boostDb),
                on = !isSameVolumeBoost(settings.boostDb, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB),
                onClick = onOpenBoost,
            )
            ActionChip("Sleep", state.sleepTimer !is SleepTimerState.Inactive, onOpenSleep)
            // Last: this is the one control that takes the listener off the player and onto a page.
            Button(attrs = {
                classes("np-goto")
                attr("type", TYPE_BUTTON)
                onClick { onOpenBook(book.id) }
            }) {
                Text("Go to book")
                Icon(WebIcon.ChevronRight, size = GOTO_ICON_SIZE)
            }
        }

        // Android's player offers this from its overflow menu. Here it sits apart from the chips,
        // because it is the one control that ends the session rather than adjusting it — and
        // without it a listener done with a book could only pause it, leaving the bar naming it
        // on every page. No confirmation, matching Android: nothing is lost, the position is
        // saved, and the book is one Play away.
        DsButton(
            kind = ButtonKind.Ghost,
            size = ButtonSize.Sm,
            onClick = onCloseBook,
            attrs = { classes("np-close-book") },
        ) {
            Text("Close book")
        }
    }
}

/** The book's authors, each a way to everything else they wrote. Absent when the book names none. */
@Composable
private fun Byline(
    authors: List<PlayerContributor>,
    onOpenContributor: (String) -> Unit,
) {
    if (authors.isEmpty()) return
    Div(attrs = { classes("np-by") }) {
        authors.forEachIndexed { index, author ->
            key(author.id) {
                if (index > 0) Span(attrs = { classes("np-sep") }) { Text(", ") }
                Button(attrs = {
                    classes("np-by-name")
                    attr("type", TYPE_BUTTON)
                    onClick { onOpenContributor(author.id) }
                }) { Text(author.name) }
            }
        }
    }
}

/** Every series the book is in, in the book's order, each with its place in that one. Absent for a standalone book. */
@Composable
private fun SeriesLine(
    series: List<PlayerSeriesRef>,
    onOpenSeries: (String) -> Unit,
) {
    if (series.isEmpty()) return
    Div(attrs = { classes("np-series") }) {
        series.forEach { membership ->
            key(membership.id) {
                Button(attrs = {
                    classes("np-series-chip")
                    attr("type", TYPE_BUTTON)
                    onClick { onOpenSeries(membership.id) }
                }) {
                    Text(membership.name)
                    membership.sequenceLabel?.let { position ->
                        Span(attrs = { classes("np-series-seq") }) { Text("#$position") }
                    }
                }
            }
        }
    }
}

/**
 * "Chapter 12 of 76 · The Shattered Plains".
 *
 * Renders nothing for a book with no marks — every alternative ("Chapter 1 of 1", a bare dash)
 * states something about a structure the book does not have.
 */
@Composable
private fun ChapterLine(chapter: PlayerChapter) {
    if (chapter.isWholeBook) return
    Div(attrs = { classes("np-ch") }) {
        Span(attrs = { classes("np-ch-n") }) { Text("Chapter ${chapter.number} of ${chapter.total}") }
        Span(attrs = { classes("np-ch-t") }) { Text(chapter.title) }
    }
}

/**
 * Previous/next chapter.
 *
 * Genuinely `disabled` rather than present-but-inert when there is nowhere to go: a listener should be
 * able to feel that Next is not available in the last chapter, and a `disabled` button is the only version
 * of that a screen reader also hears.
 */
@Composable
private fun ChapterStep(
    icon: WebIcon,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("np-chstep")
        attr("type", TYPE_BUTTON)
        attr("aria-label", label)
        attr("title", label)
        if (!enabled) attr("disabled", "")
        onClick { if (enabled) onClick() }
    }) { Icon(icon, size = CHAPTER_STEP_ICON_SIZE) }
}

@Composable
private fun PanelButton(
    icon: WebIcon,
    label: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("np-b")
        attr("type", TYPE_BUTTON)
        attr("aria-label", label)
        attr("title", label)
        onClick { onClick() }
    }) { Icon(icon, size = PANEL_ICON_SIZE) }
}

@Composable
private fun ActionChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("np-chip")
        if (on) classes("on")
        attr("type", TYPE_BUTTON)
        onClick { onClick() }
    }) { Text(label) }
}

/** "Off" or "+6 dB" — the same vocabulary the boost picker reads it out in. */
private fun boostLabel(boostDb: Float): String =
    if (isSameVolumeBoost(boostDb, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)) {
        "Boost"
    } else {
        "+${boostDb.toInt()} dB"
    }

/** The `type` every one of this panel's controls declares, so none of them submits anything. */
private const val TYPE_BUTTON = "button"

private const val COVER_RUNG = 600

private const val COVER_SIZE = 200

private const val COVER_RADIUS = 16

private const val PLAY_ICON_SIZE = 26

private const val PANEL_ICON_SIZE = 20

private const val CHAPTER_STEP_ICON_SIZE = 20

private const val GOTO_ICON_SIZE = 15
