package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * What the transport bar shows.
 *
 * **Null means no book is loaded, and the bar renders nothing** — an empty transport bar is
 * chrome that lies about there being something to play.
 */
data class TransportState(
    val title: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The bar under the shell: what is playing, where it is, and the two controls that change either.
 *
 * Presentational — every value comes from [state] and every gesture leaves through a callback, so
 * a spec can drive any moment of a listening session without a player behind it. The session that
 * supplies those callbacks ([PlaybackSession]) is what reaches the real player.
 *
 * The play control is a `<button>` with an `aria-label` rather than an icon alone: the icon is
 * `aria-hidden` by construction (see [Icon]), so without the label the single most important
 * control on the page announces itself as nothing at all.
 *
 * The scrubber holds its own drag position. Two problems, one fix: a fully controlled range
 * re-renders from the *player's* position on every tick, so mid-drag the thumb snaps back under
 * the finger to wherever the element had actually got to — and committing on `input` would issue
 * a seek per pointer sample, which on the HLS path means
 * [com.calypsan.listenup.web.playback.HtmlAudioPlayer] re-running `attach` per sample, destroying
 * and rebuilding an hls.js instance and re-fetching its playlists tens of times a second.
 * Committing once, on release, fixes both.
 */
@Composable
fun TransportBar(
    state: TransportState?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    if (state == null) return

    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = dragPositionMs ?: state.positionMs

    val label = if (state.isPlaying) "Pause" else "Play"
    Div(attrs = { classes("tport") }) {
        Button(attrs = {
            classes("tport-b")
            attr("type", "button")
            attr("aria-label", label)
            attr("title", label)
            onClick { onPlayPause() }
        }) {
            Icon(if (state.isPlaying) WebIcon.Pause else WebIcon.Play, size = TRANSPORT_ICON_SIZE)
        }

        Span(attrs = { classes("tport-t") }) { Text(state.title) }

        Span(attrs = { classes("mono", "tport-time") }) { Text(formatElapsed(shownPositionMs)) }

        // The scrubber spans the whole book, not the current file: a listener drags to a place in
        // a story, and which of the book's files that lands in is the player's problem.
        Input(type = InputType.Range) {
            classes("tport-scrub")
            attr("min", "0")
            // A zero-length range collapses the thumb onto the track and reports every drag as 0.
            attr("max", state.durationMs.coerceAtLeast(1L).toString())
            // Without this the range keeps its default step of 1 — one MILLISECOND per arrow key,
            // so a keyboard listener would need thirty thousand presses to skip half a minute.
            attr("step", STEP_MS.toString())
            attr("aria-label", "Seek")
            // The implicit `aria-valuenow` is the raw millisecond count, which a screen reader
            // reads out as "four hundred and twenty thousand". Same courtesy the play button gets.
            attr("aria-valuetext", formatElapsed(shownPositionMs))
            value(shownPositionMs.toString())
            onInput { event -> dragPositionMs = scrubbedMs(event.target.value) }
            onChange { event ->
                val target = scrubbedMs(event.target.value) ?: state.positionMs
                dragPositionMs = null
                onSeek(target)
            }
        }

        Span(attrs = { classes("mono", "tport-time") }) { Text(formatElapsed(state.durationMs)) }
    }
}

/**
 * A playback failure, said out loud.
 *
 * Rendered beside the bar rather than inside it, because the two most common failures — a prepare
 * that never returned a book, and a fatal hls.js teardown — leave nothing playing, and a message
 * that only appeared when there was already a transport bar would be a message nobody ever sees.
 *
 * Dismissable, and only dismissable: the errors reaching here are either already retried by the
 * layer below or explicitly non-recoverable, so a Retry button would be a control that cannot
 * keep its promise.
 */
@Composable
fun PlaybackNotice(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    Div(attrs = {
        classes("tport-note")
        attr("role", "alert")
    }) {
        Span(attrs = { classes("tport-note-t") }) { Text(message) }
        Button(attrs = {
            classes("tport-note-x")
            attr("type", "button")
            attr("aria-label", "Dismiss")
            attr("title", "Dismiss")
            onClick { onDismiss() }
        }) {
            Icon(WebIcon.X, size = NOTICE_ICON_SIZE)
        }
    }
}

/**
 * `H:MM:SS` once a position reaches an hour, `M:SS` below it.
 *
 * Dropping the hour field below an hour is not cosmetic: `0:02:05` and `2:05` are the same moment,
 * but a bar that always shows three fields makes a 40-minute lecture look like a 40-hour epic at a
 * glance, which is the one thing this label exists to convey.
 */
internal fun formatElapsed(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}" else "$minutes:${twoDigits(seconds)}"
}

/**
 * The element's own `value` string as a position, or null when it is not one.
 *
 * Read off the element rather than the event's `valueAsNumber` so there is exactly one parse to
 * reason about, and null rather than coerced to zero — sending a listener to the start of the book
 * is the worst available guess at what an unreadable value meant.
 */
private fun scrubbedMs(raw: String): Long? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()

private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')

private const val MILLIS_PER_SECOND = 1_000L

private const val SECONDS_PER_MINUTE = 60L

private const val SECONDS_PER_HOUR = 3_600L

/** One second per keyboard step — the unit a listener actually thinks in. */
private const val STEP_MS = 1_000L

private const val TRANSPORT_ICON_SIZE = 18

private const val NOTICE_ICON_SIZE = 15
