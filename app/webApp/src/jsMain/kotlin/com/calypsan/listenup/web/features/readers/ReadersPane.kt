package com.calypsan.listenup.web.features.readers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.util.relativeOrMonthYear
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
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
 * Capped at [COLLAPSED_READERS] with a "See all" when there are more — see [ReadersPage].
 */
@Composable
fun ReadersPanel(
    state: BookReadersUiState,
    nowMs: Long,
    onOpenProfile: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val data = state as? BookReadersUiState.Data ?: return
    val lines = flattenToLines(data.readers.readers)
    if (lines.isEmpty()) return

    val shown = lines.take(COLLAPSED_READERS)
    Panel(
        title = READERS,
        trailing = {
            if (lines.size > shown.size) {
                Button(attrs = {
                    classes("rdr-all")
                    attr("type", "button")
                    onClick { onSeeAll() }
                }) { Text("See all") }
            }
        },
    ) {
        ListeningNow(lines)
        ReaderRows(shown, nowMs, onOpenProfile)
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
                val lines = flattenToLines(state.readers.readers)
                if (lines.isEmpty()) {
                    EmptyState(title = "Nobody has started this book yet.", look = EmptyLook.Inline)
                    return@Div
                }
                ListeningNow(lines)
                ReaderRows(lines, nowMs, onOpenProfile)
            }
        }
    }
}

/** "3 listening now" — only when somebody actually is. */
@Composable
private fun ListeningNow(lines: List<ReaderLine>) {
    val listening = lines.count { it.kind is ReaderLineKind.Reading }
    if (listening == 0) return
    Div(attrs = { classes("rdr-now") }) { Text("$listening listening now") }
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
        if (kind is ReaderLineKind.Reading) classes("is-live")
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
            line.rating?.note?.let { note ->
                Span(attrs = { classes("rdr-note") }) { Text("\u201C$note\u201D") }
            }
        }
        Icon(
            when (kind) {
                is ReaderLineKind.Reading -> WebIcon.Volume
                is ReaderLineKind.Finished -> WebIcon.Check
                is ReaderLineKind.FinishedOnHardcover -> WebIcon.Check
                ReaderLineKind.Rated -> WebIcon.Star
            },
            size = MARK_SIZE,
        )
    }
}

/**
 * What one reader line says about itself.
 *
 * A Reading line with no percentage still says "Listening now": presence tells us they are on the
 * book even when no position has synced, and an empty second line would read as missing data
 * rather than as a reader we know less about.
 *
 * A read logged on Hardcover says "Read …", never "Finished …": it wasn't listened to here.
 */
internal fun stateLine(
    kind: ReaderLineKind,
    nowMs: Long,
): String =
    when (kind) {
        is ReaderLineKind.Reading -> kind.progressPct?.let { "$it% through" } ?: "Listening now"

        is ReaderLineKind.Finished -> "Finished ${relativeOrMonthYear(kind.finishedAtMs, nowMs)}"

        // Logged on Hardcover, not listened to here: it was read, never "finished" in ListenUp.
        is ReaderLineKind.FinishedOnHardcover -> "Read ${relativeOrMonthYear(kind.finishedAtMs, nowMs)}"

        // Rated without reading it here (imported history, say): no progress and no date to give.
        ReaderLineKind.Rated -> "Rated"
    }

private const val COLLAPSED_READERS = 5

private const val AVATAR_SIZE = 32

private const val MARK_SIZE = 16

private const val READERS = "Readers"

private const val HARDCOVER = "Hardcover"

private const val ALSO_ON_HARDCOVER = "Also on Hardcover"

/** The one "View profile" every reader row is described by. */
private const val OPENS_PROFILE_ID = "rdr-opens-profile"
