package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

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
    /** Current playback rate, shown on the speed control and used to size a skip. */
    val speed: Float = 1.0f,
    /** How far the back control moves, in seconds of listening. The listener's own setting. */
    val skipBackSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
    /** How far the forward control moves, in seconds of listening. The listener's own setting. */
    val skipForwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
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
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onCycleSpeed: () -> Unit,
    chapters: List<TransportChapter> = emptyList(),
    currentChapterIndex: Int? = null,
    onSeekToChapter: (Int) -> Unit = {},
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
    onSetSleepTimer: (SleepTimerMode) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onExtendSleepTimer: (Int) -> Unit = {},
) {
    if (state == null) return

    var chaptersOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = dragPositionMs ?: state.positionMs

    val label = if (state.isPlaying) "Pause" else "Play"
    ChapterPicker(
        open = chaptersOpen,
        chapters = chapters,
        currentIndex = currentChapterIndex,
        onPick = { index ->
            onSeekToChapter(index)
            chaptersOpen = false
        },
        onDismiss = { chaptersOpen = false },
    )
    SleepTimerPicker(
        open = sleepOpen,
        state = sleepTimer,
        hasChapters = chapters.isNotEmpty(),
        onSet = { mode ->
            onSetSleepTimer(mode)
            sleepOpen = false
        },
        onCancel = onCancelSleepTimer,
        onExtend = onExtendSleepTimer,
        onDismiss = { sleepOpen = false },
    )

    Div(attrs = { classes("tport") }) {
        SkipButton(
            icon = WebIcon.SkipBack,
            seconds = state.skipBackSec,
            label = "Back ${state.skipBackSec} seconds",
            onClick = onSkipBack,
        )

        Button(attrs = {
            classes("tport-b")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, label)
            attr(ATTR_TITLE, label)
            onClick { onPlayPause() }
        }) {
            Icon(if (state.isPlaying) WebIcon.Pause else WebIcon.Play, size = TRANSPORT_ICON_SIZE)
        }

        SkipButton(
            icon = WebIcon.SkipForward,
            seconds = state.skipForwardSec,
            label = "Forward ${state.skipForwardSec} seconds",
            onClick = onSkipForward,
        )

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
            attr(ATTR_ARIA_LABEL, "Seek")
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

        // A cycle rather than a menu: the ladder is nine rungs, and a listener adjusting speed is
        // hunting a feel, not picking a value. `aria-label` carries the current rate because the
        // visible text is the terse form — a screen reader saying "one point five ex" is not it.
        Button(attrs = {
            classes("tport-speed")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Playback speed ${formatSpeed(state.speed)}, change")
            attr(ATTR_TITLE, "Playback speed")
            onClick { onCycleSpeed() }
        }) {
            Text("${formatSpeed(state.speed)}\u00D7")
        }

        // Only when the book actually has marks. A control that opens an empty list is a promise
        // the book cannot keep, and plenty of audiobooks ship without chapters at all.
        if (chapters.isNotEmpty()) {
            Button(attrs = {
                classes("tport-skip")
                attr("type", "button")
                attr(ATTR_ARIA_LABEL, "Chapters")
                attr(ATTR_TITLE, "Chapters")
                onClick { chaptersOpen = true }
            }) {
                Icon(WebIcon.Hash, size = CHAPTER_ICON_SIZE)
            }
        }

        // Always offered, unlike chapters: a duration timer needs nothing from the book. The
        // armed state is on the control itself, because a timer nobody can see is one the
        // listener will not remember setting — and finding out it was armed by having the book
        // stop is the one way this feature can feel broken rather than kind.
        val armed = sleepTimer !is SleepTimerState.Inactive
        val sleepLabel = if (armed) "Sleep timer, set" else "Sleep timer"
        Button(attrs = {
            classes("tport-sleep")
            if (armed) classes("on")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, sleepLabel)
            attr(ATTR_TITLE, sleepLabel)
            onClick { sleepOpen = true }
        }) {
            Icon(WebIcon.Clock, size = SLEEP_ICON_SIZE)
        }
    }
}

/**
 * One skip control: the rotation arrow with the interval written inside it.
 *
 * The number is rendered rather than baked into the glyph because the interval is the listener's
 * own setting — a fixed "30" drawn into the icon would be a lie the moment they changed it.
 */
@Composable
private fun SkipButton(
    icon: WebIcon,
    seconds: Int,
    label: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("tport-skip")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, label)
        attr(ATTR_TITLE, label)
        onClick { onClick() }
    }) {
        Icon(icon, size = TRANSPORT_ICON_SIZE)
        // aria-hidden: the button's own label already says "Back 10 seconds", and without this a
        // screen reader appends a bare "10" to it.
        Span(attrs = {
            classes("tport-skip-n")
            attr("aria-hidden", "true")
        }) { Text(seconds.toString()) }
    }
}

/**
 * A speed as the shortest text that still reads as that speed: `1x`, `1.5x`, `1.25x`.
 *
 * Trailing zeros are dropped because the control is 40px wide and "1.00" spends a third of it
 * saying nothing.
 */
internal fun formatSpeed(speed: Float): String {
    val hundredths = (speed * HUNDREDTHS).roundToInt()
    val whole = hundredths / HUNDREDTHS
    val fraction = hundredths % HUNDREDTHS
    return when {
        fraction == 0 -> whole.toString()
        fraction % TENTHS == 0 -> "$whole.${fraction / TENTHS}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
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
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Dismiss")
            attr(ATTR_TITLE, "Dismiss")
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

/** Attribute names, named once: four buttons in this file set the same three. */
private const val ATTR_TYPE = "type"

private const val ATTR_ARIA_LABEL = "aria-label"

private const val ATTR_TITLE = "title"

private const val CHAPTER_ICON_SIZE = 18

private const val SLEEP_ICON_SIZE = 17

private const val VALUE_BUTTON = "button"

/** A speed is carried as hundredths so the ladder's quarter steps stay exact in integer maths. */
private const val HUNDREDTHS = 100

private const val TENTHS = 10
