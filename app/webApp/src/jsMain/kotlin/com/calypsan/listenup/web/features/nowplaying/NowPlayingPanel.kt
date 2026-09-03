package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.nowplaying.isSameVolumeBoost
import com.calypsan.listenup.web.design.Cover
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
 * ⛔ **[book] can be null while [state] is not.** They come from different places on purpose:
 * [state] is the player's own truth (a title captured at prepare, a live position), while [book]
 * is Room's. A book that is playing but has not reached this browser's mirror renders the panel
 * with its title and playhead and simply no links — which is what is actually known — rather than
 * withholding the whole panel or offering destinations that resolve to nothing.
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
    state: TransportState,
    book: NowPlayingBook?,
    chapters: List<TransportChapter>,
    currentChapterIndex: Int?,
    sleepTimer: SleepTimerState,
    volumeBoostDb: Float,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeekToChapter: (Int) -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenBoost: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenContributor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerDialog(open = open, title = "Now playing", panelClass = "np-dlg", onDismiss = onDismiss) {
        Div(attrs = { classes("np-body") }) {
            Cover(
                title = state.title,
                imageUrl = book?.let { coverUrl(it.bookId, it.coverHash, COVER_RUNG) },
                size = COVER_SIZE,
                radius = COVER_RADIUS,
            )
            Div(attrs = { classes("np-meta") }) {
                Div(attrs = { classes("np-t") }) { Text(state.title) }
                Byline(book, onOpenContributor)
                SeriesLine(book, onOpenSeries)
                ChapterLine(chapters, currentChapterIndex)
            }
        }

        Div(attrs = { classes("np-transport") }) {
            ChapterStep(
                icon = WebIcon.ChevronLeft,
                label = "Previous chapter",
                target = stepTarget(currentChapterIndex, chapters.size, back = true),
                onSeekToChapter = onSeekToChapter,
            )
            PanelButton(WebIcon.SkipBack, "Back ${state.skipBackSec} seconds", onSkipBack)
            Button(attrs = {
                classes("np-play")
                attr("type", TYPE_BUTTON)
                attr("aria-label", if (state.isPlaying) "Pause" else "Play")
                onClick { onPlayPause() }
            }) {
                Icon(if (state.isPlaying) WebIcon.Pause else WebIcon.Play, size = PLAY_ICON_SIZE)
            }
            PanelButton(WebIcon.SkipForward, "Forward ${state.skipForwardSec} seconds", onSkipForward)
            ChapterStep(
                icon = WebIcon.ChevronRight,
                label = "Next chapter",
                target = stepTarget(currentChapterIndex, chapters.size, back = false),
                onSeekToChapter = onSeekToChapter,
            )
        }

        Div(attrs = { classes("np-scrub-row") }) {
            Playhead(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                timeClass = "np-time",
                scrubClass = "np-scrub",
                onSeek = onSeek,
            )
        }

        Div(attrs = { classes("np-actions") }) {
            ActionChip(
                "${formatSpeed(state.speed)}×",
                state.speed != PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
                onOpenSpeed,
            )
            if (chapters.isNotEmpty()) ActionChip("Chapters", false, onOpenChapters)
            ActionChip(
                label = boostLabel(volumeBoostDb),
                on = !isSameVolumeBoost(volumeBoostDb, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB),
                onClick = onOpenBoost,
            )
            ActionChip("Sleep", sleepTimer !is SleepTimerState.Inactive, onOpenSleep)
            // Last, and only when there is a book to go to: this is the one control that takes
            // the listener off the player and onto a page.
            book?.let { known ->
                Button(attrs = {
                    classes("np-goto")
                    attr("type", TYPE_BUTTON)
                    onClick { onOpenBook(known.bookId) }
                }) {
                    Text("Go to book")
                    Icon(WebIcon.ChevronRight, size = GOTO_ICON_SIZE)
                }
            }
        }
    }
}

/** The book's authors, each a way to everything else they wrote. Absent when the book names none. */
@Composable
private fun Byline(
    book: NowPlayingBook?,
    onOpenContributor: (String) -> Unit,
) {
    val authors = book?.authors.orEmpty()
    if (authors.isEmpty()) return
    Div(attrs = { classes("np-by") }) {
        authors.forEachIndexed { index, author ->
            if (index > 0) Span(attrs = { classes("np-sep") }) { Text(", ") }
            Button(attrs = {
                classes("np-by-name")
                attr("type", TYPE_BUTTON)
                onClick { onOpenContributor(author.id) }
            }) { Text(author.name) }
        }
    }
}

/** Each series the book is in, with its place in that one. Absent for a standalone book. */
@Composable
private fun SeriesLine(
    book: NowPlayingBook?,
    onOpenSeries: (String) -> Unit,
) {
    val series = book?.series.orEmpty()
    if (series.isEmpty()) return
    Div(attrs = { classes("np-series") }) {
        series.forEach { membership ->
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

/**
 * "Chapter 12 of 76 · The Shattered Plains".
 *
 * Renders nothing when the book has no marks — every alternative ("Chapter 1 of 1", a bare dash)
 * states something about a structure the book does not have.
 */
@Composable
private fun ChapterLine(
    chapters: List<TransportChapter>,
    currentChapterIndex: Int?,
) {
    val index = currentChapterIndex ?: return
    val current = chapters.getOrNull(index) ?: return
    Div(attrs = { classes("np-ch") }) {
        Span(attrs = { classes("np-ch-n") }) { Text("Chapter ${index + 1} of ${chapters.size}") }
        Span(attrs = { classes("np-ch-t") }) { Text(current.title) }
    }
}

/**
 * Previous/next chapter.
 *
 * [target] is null at either end of the book, and the control is then genuinely `disabled` rather
 * than present-but-inert: a listener in chapter one should be able to feel that Previous is not
 * available, and a `disabled` button is the only version of that a screen reader also hears.
 */
@Composable
private fun ChapterStep(
    icon: WebIcon,
    label: String,
    target: Int?,
    onSeekToChapter: (Int) -> Unit,
) {
    Button(attrs = {
        classes("np-chstep")
        attr("type", TYPE_BUTTON)
        attr("aria-label", label)
        attr("title", label)
        if (target == null) attr("disabled", "")
        onClick { target?.let(onSeekToChapter) }
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

/**
 * Which chapter a step lands on, or null when there is none that way.
 *
 * Pure, and separate from the seek it feeds, so the ends of a book are provable without a player —
 * the same split [chapterStartMs] makes for the same reason.
 */
internal fun stepTarget(
    currentIndex: Int?,
    chapterCount: Int,
    back: Boolean,
): Int? {
    if (currentIndex == null || chapterCount == 0) return null
    val target = if (back) currentIndex - 1 else currentIndex + 1
    return target.takeIf { it in 0 until chapterCount }
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
