package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.isSameVolumeBoost
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.domain.VolumeBoostLimits
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import com.calypsan.listenup.web.design.Button as DsButton
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * The bar under the shell: what is loaded, where it is, and the controls that change either.
 *
 * Presentational: every value comes from [state] and [progress], and every gesture leaves through a
 * callback, so a spec can drive any moment of a listening session without a player behind it. The session
 * that supplies those callbacks ([PlaybackSession]) is what reaches the real player. Only a loaded book has
 * a bar: an empty transport bar is chrome that lies about there being something to play, so the host draws
 * nothing for [PlayerUiState.NothingLoaded].
 *
 * The bar shows from the moment a book is asked for: while it prepares it already names the new book, with
 * a spinner on the main button (§3.6). The main button is whatever [PlayerUiState.Loaded.primary] says
 * (Play, Pause, Retry, Listen again, …), and it acts through [onPrimary], so the bar never decides on its
 * own what a press means.
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
 * Committing once, on release, fixes both. It spans the whole book for now; PR 3 moves it to the chapter.
 */
@Composable
fun TransportBar(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    onPrimary: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onResetSpeed: () -> Unit = {},
    onSeekToChapter: (Int) -> Unit = {},
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onSetSleepTimer: (SleepTimerMode) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onExtendSleepTimer: (Int) -> Unit = {},
    boostUnavailable: Boolean = false,
    onSetBoost: (Float) -> Unit = {},
    onResetBoost: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
    onCloseBook: () -> Unit = {},
) {
    var chaptersOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var boostOpen by remember { mutableStateOf(false) }
    var expandedOpen by remember { mutableStateOf(false) }
    val settings = state.settings
    val hasChapters = state.chapters.isNotEmpty()
    ChapterPicker(
        open = chaptersOpen,
        chapters = state.chapters,
        onPick = { index ->
            onSeekToChapter(index)
            chaptersOpen = false
        },
        onDismiss = { chaptersOpen = false },
    )
    SleepTimerPicker(
        open = sleepOpen,
        state = state.sleepTimer,
        hasChapters = hasChapters,
        onSet = { mode ->
            onSetSleepTimer(mode)
            sleepOpen = false
        },
        onCancel = onCancelSleepTimer,
        onExtend = onExtendSleepTimer,
        onDismiss = { sleepOpen = false },
    )
    SpeedPicker(
        open = speedOpen,
        speed = settings.speed,
        defaultSpeed = settings.defaultSpeed,
        onSet = onSetSpeed,
        onReset = onResetSpeed,
        onDismiss = { speedOpen = false },
    )
    BoostPicker(
        open = boostOpen,
        boostDb = settings.boostDb,
        defaultBoostDb = settings.defaultBoostDb,
        unavailable = boostUnavailable,
        onSet = onSetBoost,
        onReset = onResetBoost,
        onDismiss = { boostOpen = false },
    )

    NowPlayingPanel(
        open = expandedOpen,
        state = state,
        progress = progress,
        onPrimary = onPrimary,
        onSeek = onSeek,
        onSkipBack = onSkipBack,
        onSkipForward = onSkipForward,
        onPreviousChapter = onPreviousChapter,
        onNextChapter = onNextChapter,
        onOpenSpeed = { speedOpen = true },
        onOpenChapters = { chaptersOpen = true },
        onOpenBoost = { boostOpen = true },
        onOpenSleep = { sleepOpen = true },
        onOpenBook = onOpenBook,
        onOpenSeries = onOpenSeries,
        onOpenContributor = onOpenContributor,
        onCloseBook = {
            expandedOpen = false
            onCloseBook()
        },
        onDismiss = { expandedOpen = false },
    )

    Div(attrs = { classes("tport") }) {
        Button(attrs = {
            classes("tport-expand")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Open the player")
            attr(ATTR_TITLE, "Open the player")
            onClick { expandedOpen = true }
        }) { Icon(WebIcon.ChevronUp, size = EXPAND_ICON_SIZE) }

        // Only where there is a settled position to go back from (§3.5): not while preparing or buffering.
        if (state.showsSkipBack) {
            SkipButton(
                icon = WebIcon.SkipBack,
                seconds = settings.skipBackSec,
                label = "Back ${settings.skipBackSec} seconds",
                onClick = onSkipBack,
            )
        }

        PrimaryButton(primary = state.primary, onPrimary = onPrimary)

        SkipButton(
            icon = WebIcon.SkipForward,
            seconds = settings.skipForwardSec,
            label = "Forward ${settings.skipForwardSec} seconds",
            onClick = onSkipForward,
        )

        Span(attrs = { classes("tport-t") }) { Text(state.book.title) }

        Playhead(
            positionMs = progress.bookPositionMs,
            durationMs = progress.bookDurationMs,
            timeClass = "tport-time",
            scrubClass = "tport-scrub",
            onSeek = onSeek,
        )

        SessionControls(
            speed = settings.speed,
            hasChapters = hasChapters,
            volumeBoostDb = settings.boostDb,
            sleepArmed = state.sleepTimer !is SleepTimerState.Inactive,
            onOpenSpeed = { speedOpen = true },
            onOpenChapters = { chaptersOpen = true },
            onOpenBoost = { boostOpen = true },
            onOpenSleep = { sleepOpen = true },
        )
    }
}

/**
 * The four controls that change how the session behaves rather than where it is: rate, chapter,
 * boost, sleep.
 *
 * Split out from [TransportBar] because they are one idea — every one of them opens a panel and
 * none of them touches the playhead — and because keeping them inline pushed the bar past the
 * length where you can still see its shape in one screen.
 */
@Composable
private fun SessionControls(
    speed: Float,
    hasChapters: Boolean,
    volumeBoostDb: Float,
    sleepArmed: Boolean,
    onOpenSpeed: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenBoost: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    // Opens the picker rather than cycling. Cycling made one move cheap and every other one
    // expensive — nine rungs meant eight taps to go down one — and could not reach a rate
    // between rungs at all. `aria-label` carries the current rate because the visible text is
    // the terse form: a screen reader saying "one point five ex" is not it.
    Button(attrs = {
        classes("tport-speed")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, "Playback speed ${formatSpeed(speed)}, change")
        attr(ATTR_TITLE, "Playback speed")
        onClick { onOpenSpeed() }
    }) {
        Text("${formatSpeed(speed)}\u00D7")
    }

    // Only when the book actually has marks. A control that opens an empty list is a promise
    // the book cannot keep, and plenty of audiobooks ship without chapters at all.
    // ⛔ Its own class, not `.tport-skip`: the skips step aside on a phone, and this used to wear
    // their class and vanish with them — on the one screen where finding your chapter matters most.
    if (hasChapters) {
        Button(attrs = {
            classes("tport-ch")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Chapters")
            attr(ATTR_TITLE, "Chapters")
            onClick { onOpenChapters() }
        }) {
            Icon(WebIcon.Hash, size = CHAPTER_ICON_SIZE)
        }
    }

    // Beside the sleep timer rather than with the skips. On a phone the bar has room for a thumb's
    // worth of controls, so this one — with speed and sleep — lives in the full player there, one
    // tap away, and a boost in force still shows on that panel's chip. The boost in force is on
    // the control because it is not otherwise visible — "why is this book so loud" has to have an
    // answer you can see.
    val boosted = !isSameVolumeBoost(volumeBoostDb, VolumeBoostLimits.MIN_DB)
    val boostLabel = if (boosted) "Volume boost, ${formatBoost(volumeBoostDb)}" else "Volume boost"
    Button(attrs = {
        classes("tport-boost")
        if (boosted) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, boostLabel)
        attr(ATTR_TITLE, boostLabel)
        onClick { onOpenBoost() }
    }) {
        Icon(WebIcon.Volume, size = BOOST_ICON_SIZE)
    }

    // Always offered, unlike chapters: a duration timer needs nothing from the book. The
    // armed state is on the control itself, because a timer nobody can see is one the
    // listener will not remember setting — and finding out it was armed by having the book
    // stop is the one way this feature can feel broken rather than kind.
    val sleepLabel = if (sleepArmed) "Sleep timer, set" else "Sleep timer"
    Button(attrs = {
        classes("tport-sleep")
        if (sleepArmed) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, sleepLabel)
        attr(ATTR_TITLE, sleepLabel)
        onClick { onOpenSleep() }
    }) {
        Icon(WebIcon.Clock, size = SLEEP_ICON_SIZE)
    }
}

/**
 * The one main button, in whichever of its states the shared model chose.
 *
 * ⛔ The spinner replaces the glyph rather than sitting beside it: the control keeps its size and
 * position, so a stall does not shuffle the whole transport row sideways under the reader's cursor.
 * ⛔ Announced, not merely drawn: a spinner is invisible to a screen reader, and "Pause" while the
 * player is stalled is a lie about what pressing it would achieve. A browser that refused to start
 * the audio ([PrimaryControl.TapToPlay]) gets Play with a ring: one more tap is all it needs.
 */
@Composable
private fun PrimaryButton(
    primary: PrimaryControl,
    onPrimary: () -> Unit,
) {
    val label = primaryLabel(primary)
    Button(attrs = {
        classes("tport-b")
        if (primary == PrimaryControl.TapToPlay) classes("ring")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, label)
        attr(ATTR_TITLE, label)
        onClick { onPrimary() }
    }) {
        if (primary == PrimaryControl.Busy) {
            Div(attrs = {
                classes("tport-wait")
                attr("aria-hidden", "true")
            })
        } else {
            Icon(primaryIcon(primary), size = TRANSPORT_ICON_SIZE)
        }
    }
}

/** What the main button says it will do: its accessible name and its tooltip. */
internal fun primaryLabel(primary: PrimaryControl): String =
    when (primary) {
        PrimaryControl.Pause -> "Pause"
        PrimaryControl.Play, PrimaryControl.TapToPlay -> "Play"
        PrimaryControl.Busy -> "Loading"
        PrimaryControl.Retry -> "Retry"
        PrimaryControl.ListenAgain -> "Listen again"
        PrimaryControl.SignIn -> "Sign in"
        PrimaryControl.OpenInbox -> "Open the Inbox"
        PrimaryControl.Close -> "Close book"
    }

/** The glyph for every state but [PrimaryControl.Busy], which draws a spinner instead. */
internal fun primaryIcon(primary: PrimaryControl): WebIcon =
    when (primary) {
        PrimaryControl.Pause -> WebIcon.Pause
        PrimaryControl.Retry -> WebIcon.Refresh
        PrimaryControl.SignIn -> WebIcon.LogIn
        PrimaryControl.OpenInbox -> WebIcon.Inbox
        PrimaryControl.Close -> WebIcon.X
        PrimaryControl.Play, PrimaryControl.TapToPlay, PrimaryControl.ListenAgain, PrimaryControl.Busy -> WebIcon.Play
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
 * A playback failure, or a known "can't play", said out loud: the shared model's typed error in the
 * browser's words, a title and, when there is one, its cause.
 *
 * Rendered beside the bar rather than inside it, so it reads the same whether or not the bar has room.
 * The strip stays until the redesigned mini player takes over its job (PR 2).
 *
 * Retry is offered when, and only when, the main button would retry ([PrimaryControl.Retry]): a dropped
 * connection or a prepare that failed can be tried again, but a held book's or a removed book's refusal
 * cannot, and a Retry there would be a control that cannot keep its promise. Dismiss keeps the bar.
 */
@Composable
fun PlaybackNotice(
    error: AppError?,
    primary: PrimaryControl?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (error == null) return

    Div(attrs = {
        classes("tport-note")
        attr("role", "alert")
    }) {
        Span(attrs = { classes("tport-note-t") }) {
            Text(error.playerTitle())
            error.playerCause()?.let { cause -> Text(" $cause") }
        }
        if (primary == PrimaryControl.Retry) {
            DsButton(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = onRetry) { Text("Retry") }
        }
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

/** The error the notice reports for [state]: a failure, or a known reason the book cannot play. */
internal fun noticeErrorOf(state: PlayerUiState): AppError? {
    val phase = (state as? PlayerUiState.Loaded)?.phase
    return (phase as? PlayerPhase.Failed)?.error ?: (phase as? PlayerPhase.Unavailable)?.reason
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

/**
 * Elapsed time, the seek range, and total time — the three things that always move together.
 *
 * One implementation, used by the docked bar and the expanded panel. The drag position lives HERE
 * rather than in either caller, because it is the reason this is a component at all: a fully
 * controlled range re-renders from the *player's* position on every tick, so mid-drag the thumb
 * snaps back under the finger. Two copies of that fix would be two chances to get it wrong, and
 * the elapsed label has to read the same held value the thumb does or they disagree while dragging.
 *
 * [timeClass] and [scrubClass] are the caller's, because the bar and the panel size these very
 * differently and the geometry is the only thing that differs between them.
 */
@Composable
internal fun Playhead(
    positionMs: Long,
    durationMs: Long,
    timeClass: String,
    scrubClass: String,
    onSeek: (Long) -> Unit,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = dragPositionMs ?: positionMs

    Span(attrs = { classes("mono", timeClass) }) { Text(formatElapsed(shownPositionMs)) }

    // The scrubber spans the whole book, not the current file: a listener drags to a place in
    // a story, and which of the book's files that lands in is the player's problem.
    Input(type = InputType.Range) {
        classes(scrubClass)
        attr("min", "0")
        // A zero-length range collapses the thumb onto the track and reports every drag as 0.
        attr("max", durationMs.coerceAtLeast(1L).toString())
        // Without this the range keeps its default step of 1 — one MILLISECOND per arrow key,
        // so a keyboard listener would need thirty thousand presses to skip half a minute.
        attr("step", STEP_MS.toString())
        attr(ATTR_ARIA_LABEL, "Seek")
        // The implicit `aria-valuenow` is the raw millisecond count, which a screen reader
        // reads out as "four hundred and twenty thousand". Same courtesy the play button gets.
        // "3:30 of 42:00", not just "3:30": where you are only means something against the whole.
        attr("aria-valuetext", "${formatElapsed(shownPositionMs)} of ${formatElapsed(durationMs)}")
        value(shownPositionMs.toString())
        onInput { event -> dragPositionMs = scrubbedMs(event.target.value) }
        onChange { event ->
            val target = scrubbedMs(event.target.value) ?: positionMs
            dragPositionMs = null
            onSeek(target)
        }
    }

    Span(attrs = { classes("mono", timeClass) }) { Text(formatElapsed(durationMs)) }
}

private const val MILLIS_PER_SECOND = 1_000L

private const val SECONDS_PER_MINUTE = 60L

private const val SECONDS_PER_HOUR = 3_600L

/** One second per keyboard step — the unit a listener actually thinks in. */
private const val STEP_MS = 1_000L

private const val TRANSPORT_ICON_SIZE = 18

private const val EXPAND_ICON_SIZE = 18

private const val NOTICE_ICON_SIZE = 15

/** Attribute names, named once: four buttons in this file set the same three. */
private const val ATTR_TYPE = "type"

private const val ATTR_ARIA_LABEL = "aria-label"

private const val ATTR_TITLE = "title"

private const val CHAPTER_ICON_SIZE = 18

private const val SLEEP_ICON_SIZE = 17

private const val BOOST_ICON_SIZE = 17

private const val VALUE_BUTTON = "button"

/** A speed is carried as hundredths so the ladder's quarter steps stay exact in integer maths. */
private const val HUNDREDTHS = 100

private const val TENTHS = 10
