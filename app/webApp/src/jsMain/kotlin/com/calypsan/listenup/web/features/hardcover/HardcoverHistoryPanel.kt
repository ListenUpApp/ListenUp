package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The card that sends the books finished before connecting (#1540), between who you are and Sync, on the
 * paper panel: the offer, the send in progress, then what it came to. Nothing for None or Available —
 * Available is the quiet row in Sync ([EarlierBooksRow]). Every string is the English text of en.json's
 * `hardcover.history_*` keys.
 *
 * @param onShowNeedsMatch Scrolls the Needs a match panel into view, from Done's "4 need a match".
 */
@Composable
internal fun HistoryCard(
    history: HardcoverHistory,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    onShowNeedsMatch: () -> Unit,
) {
    when (history) {
        is HardcoverHistory.Offer -> Panel { Offer(history.bookCount, onSend, onDismiss) }
        is HardcoverHistory.Sending -> Panel { Sending(history.sentBooks, history.totalBooks) }
        is HardcoverHistory.Done -> Panel { Done(history.sentBooks, history.needsMatchBooks, onDismiss, onShowNeedsMatch) }
        HardcoverHistory.None, is HardcoverHistory.Available -> Unit
    }
}

/**
 * "Send earlier books": the quiet row inside Sync after "Not now", while earlier books wait. It sends in
 * place — a Send button, never a chevron.
 */
@Composable
internal fun EarlierBooksRow(
    books: Int,
    onSend: () -> Unit,
) {
    Div(attrs = { classes("hc-sync", "hc-earlier") }) {
        Span(attrs = {
            classes("hc-sync-i")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(WebIcon.Clock, size = ROW_ICON) }
        Span(attrs = { classes("hc-earlier-t") }) {
            Text("Send earlier books")
            Span(attrs = { classes("hc-earlier-d") }) {
                Text(countedBooks(books, one = "1 finished before you connected") { "$it finished before you connected" })
            }
        }
        Button(kind = ButtonKind.Secondary, onClick = onSend, label = "Send earlier books to Hardcover") { Text("Send") }
    }
}

@Composable
private fun Offer(
    books: Int,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
) {
    Div(attrs = { classes("hc-history") }) {
        Div(attrs = { classes("hc-history-h") }) {
            Tile(WebIcon.Clock)
            H2(attrs = { classes("hc-history-t") }) { Text("Send your earlier listening?") }
        }
        P(attrs = { classes("hc-history-p") }) {
            Text(
                countedBooks(
                    books,
                    one = "You finished 1 book in ListenUp before connecting. Send it to Hardcover as read, $WHEN_YOU_READ",
                ) { "You finished $it books in ListenUp before connecting. Send them to Hardcover as read, $WHEN_YOU_READ" },
            )
        }
        Div(attrs = { classes("hc-history-acts") }) {
            Button(kind = ButtonKind.Primary, onClick = onSend) { Text(countedBooks(books, one = "Send 1 book") { "Send $it books" }) }
            Button(kind = ButtonKind.Ghost, onClick = onNotNow) { Text("Not now") }
        }
    }
}

@Composable
private fun Sending(
    sent: Int,
    total: Int,
) {
    Div(attrs = { classes("hc-history") }) {
        Div(attrs = {
            classes("hc-history-h")
            attr(ROLE, STATUS)
        }) {
            Tile(WebIcon.Clock)
            Span(attrs = { classes("hc-history-t") }) {
                Text(if (total == 1) "Sending 1 book…" else "Sending $sent of $total books…")
            }
        }
        ProgressBar(
            value = if (total == 0) 0f else sent.toFloat() / total,
            label = "Sending earlier books",
            attrs = { attr("aria-valuetext", "$sent of $total") },
        )
        P(attrs = { classes("hc-history-p", "hc-history-note") }) {
            Text("You can leave this screen — it keeps going. Your new listening is sent first.")
        }
    }
}

@Composable
private fun Done(
    sent: Int,
    needsMatch: Int,
    onDismiss: () -> Unit,
    onShowNeedsMatch: () -> Unit,
) {
    Div(attrs = { classes("hc-history", "hc-history-done") }) {
        Tile(WebIcon.Check, done = true)
        Div(attrs = {
            classes("hc-history-text")
            attr(ROLE, STATUS)
        }) {
            Span(attrs = { classes("hc-history-t") }) { Text(doneWords(sent, needsMatch)) }
            if (needsMatch > 0) {
                Button(kind = ButtonKind.Ghost, onClick = onShowNeedsMatch, attrs = { classes("hc-history-link") }) {
                    Text(if (needsMatch == 1) "1 needs a match" else "$needsMatch need a match")
                }
            }
        }
        Button(kind = ButtonKind.Icon, onClick = onDismiss, label = "Dismiss") { Icon(WebIcon.X, size = BUTTON_ICON) }
    }
}

/**
 * What a finished send came to. Nothing sent yet says what it waits for, never "Sent 0" (D4):
 * en.json's `history_none_sent`, `history_sent`, `history_sent_all` and their `_one` forms.
 */
private fun doneWords(
    sent: Int,
    needsMatch: Int,
): String =
    when {
        sent == 0 && needsMatch == 1 -> "1 book needs a match before it can be sent"
        sent == 0 && needsMatch > 1 -> "$needsMatch books need a match before they can be sent"
        sent == 1 -> "Sent 1 book to Hardcover"
        needsMatch == 0 && sent > 1 -> "Sent all $sent books to Hardcover"
        else -> "Sent $sent books to Hardcover"
    }

@Composable
private fun Tile(
    icon: WebIcon,
    done: Boolean = false,
) {
    Span(attrs = {
        classes("hc-history-i")
        if (done) classes("is-done")
        attr(ARIA_HIDDEN, "true")
    }) { Icon(icon, size = TILE_ICON) }
}

/** [one] for a count of one; [many] with the count otherwise. */
private fun countedBooks(
    count: Int,
    one: String,
    many: (Int) -> String,
): String = if (count == 1) one else many(count)

/** The offer body's shared ending, in both its counts. */
private const val WHEN_YOU_READ = "with when you started and finished."
private const val ARIA_HIDDEN = "aria-hidden"
private const val ROLE = "role"
private const val STATUS = "status"
private const val TILE_ICON = 20
private const val ROW_ICON = 22
private const val BUTTON_ICON = 16
