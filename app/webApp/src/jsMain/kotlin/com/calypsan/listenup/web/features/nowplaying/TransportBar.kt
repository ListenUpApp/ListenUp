package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
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
 */
@Composable
fun TransportBar(
    state: TransportState?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    if (state == null) return

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

        Span(attrs = { classes("mono", "tport-time") }) { Text(formatElapsed(state.positionMs)) }

        // The scrubber spans the whole book, not the current file: a listener drags to a place in
        // a story, and which of the book's files that lands in is the player's problem.
        Input(type = InputType.Range) {
            classes("tport-scrub")
            attr("min", "0")
            // A zero-length range collapses the thumb onto the track and reports every drag as 0.
            attr("max", state.durationMs.coerceAtLeast(1L).toString())
            attr("aria-label", "Seek")
            value(state.positionMs.toString())
            onInput { event -> onSeek(event.value?.toLong() ?: 0L) }
        }

        Span(attrs = { classes("mono", "tport-time") }) { Text(formatElapsed(state.durationMs)) }
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

private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')

private const val MILLIS_PER_SECOND = 1_000L

private const val SECONDS_PER_MINUTE = 60L

private const val SECONDS_PER_HOUR = 3_600L

private const val TRANSPORT_ICON_SIZE = 18
