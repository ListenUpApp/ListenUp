package com.calypsan.listenup.web.features.readers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.util.relativeOrMonthYear
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.focusAsLanding
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Who else is on this book — the Book Detail side panel.
 *
 * ⛔ Non-critical, and it renders that way: Loading and Error draw **nothing at all**. A book's
 * page is not broken because the readership call is slow or the mirror is unreadable, and a panel
 * that said "couldn't load readers" would make it look like it was. That is the same call Compose
 * and iOS make, for the same reason.
 *
 * Capped at [COLLAPSED_READERS] (spec §2.10: three, on every platform) with "See all N readers" below the rows when
 * there are more — see [ReadersPage]. The panel is the page's `#readers` anchor: the hero's social line scrolls to it.
 */
@Composable
fun ReadersPanel(
    state: BookReadersUiState,
    nowMs: Long,
    onOpenProfile: (String) -> Unit,
    onSeeAll: () -> Unit,
    land: Boolean = false,
    onLanded: () -> Unit = {},
) {
    val data = state as? BookReadersUiState.Data ?: return
    val lines = data.lines
    if (lines.isEmpty()) return

    // Home's finishers line (spec §6.4): once there are readers to show, bring the panel into view and its heading into
    // focus, as Discover's leaderboard landing does. Nothing to land on while the readers load.
    val currentOnLanded by rememberUpdatedState(onLanded)
    LaunchedEffect(land) {
        if (!land) return@LaunchedEffect
        val panel = document.getElementById(READERS_PANEL_ID) as? HTMLElement ?: return@LaunchedEffect
        panel.scrollIntoView()
        (panel.querySelector("h2") as? HTMLElement)?.let(::focusAsLanding)
        currentOnLanded()
    }

    val shown = lines.take(COLLAPSED_READERS)
    Panel(title = READERS, elementId = READERS_PANEL_ID) {
        ReadingCount(lines)
        ReaderRows(shown, nowMs, onOpenProfile)
        // Below the rows, full width, as the boards draw it: the way to everyone past the first three.
        if (lines.size > shown.size) {
            Button(attrs = {
                classes("rdr-all")
                attr("type", "button")
                onClick { onSeeAll() }
            }) { Text("See all ${lines.size} readers") }
        }
    }
}

/**
 * Everyone on this book, uncapped — where the panel's "See all" leads.
 *
 * Unlike the panel, this page does answer for Loading and Error: someone who followed a link here
 * asked for exactly this list, and showing them an empty page would be the lie the panel's silence
 * is not.
 */
@Composable
fun ReadersPage(
    state: BookReadersUiState,
    bookTitle: String,
    nowMs: Long,
    onOpenProfile: (String) -> Unit,
    onOpenBook: () -> Unit,
) {
    Div(attrs = { classes("rdr") }) {
        Breadcrumb(trail = listOf(bookTitle, READERS), onNavigate = { onOpenBook() })
        PageHeader(title = READERS)

        when (state) {
            BookReadersUiState.Loading -> {
                Div(attrs = { classes("skel", "rdr-skel") })
            }

            BookReadersUiState.NoReaders -> {
                EmptyState(title = "Nobody has started this book yet.", look = EmptyLook.Inline)
            }

            is BookReadersUiState.Error -> {
                EmptyState(title = "Couldn't load who is reading this. Try again in a moment.", look = EmptyLook.Inline)
            }

            is BookReadersUiState.Data -> {
                val lines = state.lines
                if (lines.isEmpty()) {
                    EmptyState(title = "Nobody has started this book yet.", look = EmptyLook.Inline)
                    return@Div
                }
                ReadingCount(lines)
                ReaderRows(lines, nowMs, onOpenProfile)
            }
        }
    }
}

/** "3 reading": people partway through — only when somebody is. Never "listening now": progress is not presence. */
@Composable
private fun ReadingCount(lines: List<ReaderLine>) {
    val reading = lines.count { it.kind is ReaderLineKind.Reading }
    if (reading == 0) return
    Div(attrs = { classes("rdr-now") }) { Text("$reading reading") }
}

@Composable
private fun ReaderRows(
    lines: List<ReaderLine>,
    nowMs: Long,
    onOpenProfile: (String) -> Unit,
) {
    Div(attrs = { classes("rdr-list") }) {
        // What every row does, said once and pointed at by each: the row's own words say who, not that
        // pressing it opens their profile.
        Span(attrs = {
            id(OPENS_PROFILE_ID)
            attr("hidden", "")
        }) { Text("View profile") }
        lines.forEach { line ->
            key(line.stableKey()) {
                ReaderRow(line, nowMs, onOpenProfile)
            }
        }
    }
}

/**
 * A line's identity: its reader plus which of their states it is. The reader alone is not unique —
 * a re-reader has a Reading line and a Finished line per pass.
 */
private fun ReaderLine.stableKey(): String =
    when (val lineKind = kind) {
        is ReaderLineKind.Reading -> "$userId:reading"
        is ReaderLineKind.Finished -> "$userId:finished:${lineKind.finishedAtMs}"
        is ReaderLineKind.FinishedOnHardcover -> "$userId:hardcover:${lineKind.finishedAtMs}"
        ReaderLineKind.Rated -> "$userId:rated"
    }

/**
 * One line, which is one *state* of one reader — not one reader.
 *
 * A reader who is on their third pass through a book has a Reading line and two Finished lines,
 * because [flattenToLines] flattens finishes individually. That is deliberate: "Finished twice"
 * collapses a re-read into a statistic, and the dates are the interesting part.
 */
@Composable
private fun ReaderRow(
    line: ReaderLine,
    nowMs: Long,
    onOpenProfile: (String) -> Unit,
) {
    val name = if (line.isYou) "You" else line.name
    val kind = line.kind
    Button(attrs = {
        classes("rdr-row")
        // Live only for you, while the player plays this book: the one listener we know is listening now.
        if (kind is ReaderLineKind.Reading && kind.isLive) classes("is-live")
        attr("type", "button")
        attr("aria-describedby", OPENS_PROFILE_ID)
        onClick { onOpenProfile(line.userId) }
    }) {
        UserAvatar(userId = line.userId, name = name, size = AVATAR_SIZE)
        Div(attrs = { classes("rdr-who") }) {
            Div(attrs = { classes("rdr-nl") }) {
                // Two lines, then an ellipsis — with the whole name on hover, so a long one is never lost.
                Span(attrs = {
                    classes("rdr-n")
                    attr("title", name)
                }) { Text(name) }
                // The rating rides on a person's first line only — see `flattenToLines`.
                line.rating?.let { RatingStars(halfStars = it.halfStars) }
            }
            if (kind is ReaderLineKind.FinishedOnHardcover) {
                // Where the read was logged, named in text — never the service's logo.
                // Read aloud as "Read October 2018 on Hardcover", not a run-on "2018Hardcover".
                Div(attrs = { classes("rdr-sl") }) {
                    Span(attrs = { classes("rdr-s") }) {
                        Text(stateLine(kind, nowMs))
                        Span(attrs = { classes("sr-only") }) { Text(" on") }
                    }
                    Span(attrs = { classes("rdr-src") }) { Text(HARDCOVER) }
                }
            } else if (kind is ReaderLineKind.Finished && kind.alsoOnHardcover) {
                // One listen, logged here and on Hardcover. Drawn as the date beside a label; spoken as
                // one phrase within the row button's name, never as a stray "Also on Hardcover".
                val finished = stateLine(kind, nowMs)
                Div(attrs = { classes("rdr-sl") }) {
                    Span(attrs = {
                        classes("rdr-s")
                        attr("aria-hidden", "true")
                    }) { Text(finished) }
                    Span(attrs = {
                        classes("rdr-src")
                        attr("aria-hidden", "true")
                    }) { Text(ALSO_ON_HARDCOVER) }
                    Span(attrs = { classes("sr-only") }) { Text("$finished, also on Hardcover") }
                }
            } else {
                Span(attrs = { classes("rdr-s") }) { Text(stateLine(kind, nowMs)) }
            }
            // The bar replaces the number (spec §5.2); the number is in the bar's name, so the row still says it.
            if (kind is ReaderLineKind.Reading) {
                kind.progressPct?.let { pct ->
                    ProgressBar(value = pct / PERCENT, label = "$pct percent", attrs = { classes("rdr-bar") })
                }
            }
            line.rating?.note?.let { note ->
                Span(attrs = { classes("rdr-note") }) { Text("\u201C$note\u201D") }
            }
        }
        // A reading row that is not live carries no mark: its bar says how far, and nothing claims they are listening.
        val mark =
            when (kind) {
                is ReaderLineKind.Reading -> if (kind.isLive) WebIcon.Volume else null
                is ReaderLineKind.Finished -> WebIcon.Check
                is ReaderLineKind.FinishedOnHardcover -> WebIcon.Check
                ReaderLineKind.Rated -> WebIcon.Star
            }
        mark?.let { Icon(it, size = MARK_SIZE) }
    }
}

/**
 * What one reader line says about itself.
 *
 * A Reading line says "Listening" — or "Listening now" when it is live (yours, while the player plays this book) —
 * with or without a percentage: the progress is the bar under it, never a number (spec §5.2).
 *
 * A read logged on Hardcover says "Read …", never "Finished …": it wasn't listened to here.
 */
internal fun stateLine(
    kind: ReaderLineKind,
    nowMs: Long,
): String =
    when (kind) {
        is ReaderLineKind.Reading -> if (kind.isLive) "Listening now" else "Listening"

        is ReaderLineKind.Finished -> "Finished ${relativeOrMonthYear(kind.finishedAtMs, nowMs)}"

        // Logged on Hardcover, not listened to here: it was read, never "finished" in ListenUp.
        is ReaderLineKind.FinishedOnHardcover -> "Read ${relativeOrMonthYear(kind.finishedAtMs, nowMs)}"

        // Rated without reading it here (imported history, say): no progress and no date to give.
        ReaderLineKind.Rated -> "Rated"
    }

private const val COLLAPSED_READERS = 3

/** A percentage as a fraction. */
private const val PERCENT = 100f

/** The Book Detail readers panel's element id: the social line's `#readers` link, and a landing's target. */
internal const val READERS_PANEL_ID = "readers"

private const val AVATAR_SIZE = 32

private const val MARK_SIZE = 16

private const val READERS = "Readers"

private const val HARDCOVER = "Hardcover"

private const val ALSO_ON_HARDCOVER = "Also on Hardcover"

/** The one "View profile" every reader row is described by. */
private const val OPENS_PROFILE_ID = "rdr-opens-profile"
