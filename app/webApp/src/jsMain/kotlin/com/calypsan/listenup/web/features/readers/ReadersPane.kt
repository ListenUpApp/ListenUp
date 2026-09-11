package com.calypsan.listenup.web.features.readers

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.util.relativeOrMonthYear
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
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
        title = "Readers",
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
        Breadcrumb(trail = listOf(bookTitle, "Readers"), onNavigate = { onOpenBook() })
        H1(attrs = { classes("rdr-t") }) { Text("Readers") }

        when (state) {
            BookReadersUiState.Loading -> {
                Div(attrs = { classes("skel", "rdr-skel") })
            }

            BookReadersUiState.NoReaders -> {
                P(attrs = { classes("rdr-none") }) { Text("Nobody has started this book yet.") }
            }

            is BookReadersUiState.Error -> {
                P(attrs = { classes("rdr-none") }) { Text("Couldn't load who is reading this. Try again in a moment.") }
            }

            is BookReadersUiState.Data -> {
                val lines = flattenToLines(state.readers.readers)
                if (lines.isEmpty()) {
                    P(attrs = { classes("rdr-none") }) { Text("Nobody has started this book yet.") }
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
        lines.forEach { line -> ReaderRow(line, nowMs, onOpenProfile) }
    }
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
        onClick { onOpenProfile(line.userId) }
    }) {
        UserAvatar(userId = line.userId, name = name, size = AVATAR_SIZE)
        Div(attrs = { classes("rdr-who") }) {
            Span(attrs = { classes("rdr-n") }) { Text(name) }
            Span(attrs = { classes("rdr-s") }) { Text(stateLine(kind, nowMs)) }
        }
        Icon(
            if (kind is ReaderLineKind.Reading) WebIcon.Volume else WebIcon.Check,
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
 */
internal fun stateLine(
    kind: ReaderLineKind,
    nowMs: Long,
): String =
    when (kind) {
        is ReaderLineKind.Reading -> kind.progressPct?.let { "$it% through" } ?: "Listening now"
        is ReaderLineKind.Finished -> "Finished ${relativeOrMonthYear(kind.finishedAtMs, nowMs)}"
    }

private const val COLLAPSED_READERS = 5

private const val AVATAR_SIZE = 32

private const val MARK_SIZE = 16
