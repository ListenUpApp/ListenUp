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
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
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
 * Committing once, on release, fixes both. On the bar it spans the chapter (M10); the panel's spans the book.
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

    val line = MiniPlayerLines.of(state)
    val tinted = line is MiniPlayerLine.Problem && line.isTinted
    val authorLine = state.book.authors.joinToString(", ") { it.name }
    Div(attrs = {
        classes("tport")
        if (tinted) classes("problem")
        attr("role", "region")
        attr(ATTR_ARIA_LABEL, "Player")
    }) {
        // Phone only (CSS): the chapter line along the bar's top edge. Passive; the body's line says the time.
        Div(attrs = {
            classes("tport-line")
            attr("aria-hidden", "true")
        }) {
            Div(attrs = {
                classes("tport-line-fill")
                style { width((progress.chapterFraction * PERCENT).percent) }
            })
        }

        // The book is the target (M1): one button into Now Playing, named for the book and where it is.
        // A `<button>` until PR 3 gives Now Playing a route to link to.
        Button(attrs = {
            classes("tport-body")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Open Now Playing: ${state.book.title}, ${miniLineLabel(line, progress)}")
            onClick { expandedOpen = true }
        }) {
            Span(attrs = { classes("tport-cover") }) {
                Cover(
                    title = state.book.title,
                    imageUrl = coverUrl(state.book.id, state.book.coverHash, BAR_COVER_RUNG),
                    size = null,
                    radius = BAR_COVER_RADIUS,
                    decorative = true,
                )
            }
            Span(attrs = { classes("tport-text") }) {
                Span(attrs = { classes("tport-t") }) { Text(state.book.title) }
                Span(attrs = { classes("tport-sub", "desk") }) { Text(miniLineText(line, progress, MiniLineFit.Desktop, authorLine)) }
                Span(attrs = { classes("tport-sub", "phone") }) { Text(miniLineText(line, progress, MiniLineFit.Phone, authorLine)) }
            }
        }

        Div(attrs = { classes("tport-mid") }) {
            Div(attrs = { classes("tport-transport") }) {
                // Its slot is kept while hidden (§3.5's stalls), so the main button never moves under the cursor.
                SkipButton(
                    icon = WebIcon.SkipBack,
                    seconds = settings.skipBackSec,
                    label = "Skip back ${settings.skipBackSec} seconds",
                    extraClass = "tport-back",
                    shown = state.showsSkipBack,
                    onClick = onSkipBack,
                )
                BarPrimary(state = state, line = line, onPrimary = onPrimary, onKeepListening = onCancelSleepTimer)
                SkipButton(
                    icon = WebIcon.SkipForward,
                    seconds = settings.skipForwardSec,
                    label = "Skip forward ${settings.skipForwardSec} seconds",
                    extraClass = "tport-fwd",
                    onClick = onSkipForward,
                )
            }
            Div(attrs = { classes("tport-head") }) {
                Playhead(
                    positionMs = progress.chapterPositionMs,
                    durationMs = progress.chapterDurationMs,
                    timeClass = "tport-time",
                    scrubClass = "tport-scrub",
                    startMs = state.chapter.startMs,
                    label = "Chapter position",
                    showsRemaining = true,
                    onSeek = onSeek,
                )
            }
        }

        Div(attrs = { classes("tport-settings") }) {
            SessionControls(
                speed = settings.speed,
                hasChapters = hasChapters,
                sleepTimer = state.sleepTimer,
                onOpenSpeed = { speedOpen = true },
                onOpenChapters = { chaptersOpen = true },
                onOpenSleep = { sleepOpen = true },
            )
        }

        Button(attrs = {
            classes("tport-expand")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Open Now Playing")
            attr(ATTR_TITLE, "Open Now Playing")
            attr("aria-expanded", expandedOpen.toString())
            onClick { expandedOpen = true }
        }) { Icon(WebIcon.ChevronUp, size = EXPAND_ICON_SIZE) }
    }
}

/**
 * Speed · Sleep · Chapters: the three that change how this book plays rather than where it is (§5.1 M13, M16,
 * M14). Volume boost left the bar with the redesign; it is one tap away in Now Playing until PR 4 moves it
 * into More.
 */
@Composable
private fun SessionControls(
    speed: Float,
    hasChapters: Boolean,
    sleepTimer: SleepTimerState,
    onOpenSpeed: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    // Opens the picker rather than cycling. Cycling made one move cheap and every other one
    // expensive — nine rungs meant eight taps to go down one — and could not reach a rate
    // between rungs at all. `aria-label` carries the current rate because the visible text is
    // the terse form: a screen reader saying "one point five ex" is not it.
    Button(attrs = {
        classes("tport-speed")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, "Playback speed, ${formatSpeed(speed)} times")
        attr(ATTR_TITLE, "Playback speed")
        onClick { onOpenSpeed() }
    }) { Text("${formatSpeed(speed)}\u00D7") }

    // Always offered, unlike chapters: a duration timer needs nothing from the book. Armed, the countdown IS the
    // control, in gold (§3.9): a timer nobody can see is one the listener forgets setting — and finding out it was
    // armed by having the book stop is the one way this feature can feel broken rather than kind.
    val active = sleepTimer as? SleepTimerState.Active
    val sleepLabel = active?.let { "Sleep timer, ${it.formatRemaining()} left" } ?: "Sleep timer"
    Button(attrs = {
        classes("tport-sleep")
        if (active != null) classes("armed")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, sleepLabel)
        attr(ATTR_TITLE, sleepLabel)
        onClick { onOpenSleep() }
    }) {
        Icon(WebIcon.Moon, size = SLEEP_ICON_SIZE)
        if (active != null) {
            Span(attrs = { attr("aria-hidden", "true") }) { Text(active.formatRemaining()) }
        }
    }

    // Only when the book actually has marks. A control that opens an empty list is a promise
    // the book cannot keep, and plenty of audiobooks ship without chapters at all.
    // ⛔ Its own class, not `.tport-skip`: the skips step aside on a phone, and this used to wear
    // their class and vanish with them.
    if (hasChapters) {
        Button(attrs = {
            classes("tport-ch")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Chapters")
            attr(ATTR_TITLE, "Chapters")
            onClick { onOpenChapters() }
        }) { Icon(WebIcon.ListOrdered, size = CHAPTER_ICON_SIZE) }
    }
}

/**
 * The bar's one main button, as the shared primary says: a coral circle, a labelled pill for a way out, or ✕.
 *
 * ⛔ The spinner replaces the glyph rather than sitting beside it: the control keeps its size and
 * position, so a stall does not shuffle the transport row sideways under the reader's cursor.
 * ⛔ Announced, not merely drawn: a spinner is invisible to a screen reader, and "Pause" while the
 * player is stalled is a lie about what pressing it would achieve. A browser that refused to start
 * the audio ([PrimaryControl.TapToPlay]) gets Play with a ring: one more tap is all it needs.
 *
 * During the sleep fade ([MiniPlayerLine.FadingOut]) the mini player's button is Keep listening, which cancels
 * the timer and restores full volume ([onKeepListening]); Now Playing keeps its Pause (Simon, 2026-10-14).
 */
@Composable
private fun BarPrimary(
    state: PlayerUiState.Loaded,
    line: MiniPlayerLine,
    onPrimary: () -> Unit,
    onKeepListening: () -> Unit,
) {
    val primary = state.primary
    when {
        line == MiniPlayerLine.FadingOut -> BarPill("Keep listening", label = null, quiet = true, onClick = onKeepListening)
        primary == PrimaryControl.Retry -> BarPill("Retry", label = null, quiet = false, onClick = onPrimary)
        primary == PrimaryControl.SignIn -> BarPill("Sign in", label = null, quiet = false, onClick = onPrimary)
        primary == PrimaryControl.OpenInbox -> BarPill("Open Inbox", label = null, quiet = false, onClick = onPrimary)
        primary == PrimaryControl.ListenAgain ->
            BarPill("Listen again", label = "Listen again to ${state.book.title}", quiet = true, onClick = onPrimary)

        primary == PrimaryControl.Close -> {
            Button(attrs = {
                classes("tport-close")
                attr(ATTR_TYPE, VALUE_BUTTON)
                attr(ATTR_ARIA_LABEL, "Close book")
                attr(ATTR_TITLE, "Close book")
                onClick { onPrimary() }
            }) { Icon(WebIcon.X, size = TRANSPORT_ICON_SIZE) }
        }

        else -> {
            val label = primaryLabel(primary, state.phase)
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
    }
}

/** A way out that needs its word: Retry, Sign in, Open Inbox, Listen again, Keep listening. [quiet] is outlined. */
@Composable
private fun BarPill(
    text: String,
    label: String?,
    quiet: Boolean,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("tport-pill")
        if (quiet) classes("quiet")
        attr(ATTR_TYPE, VALUE_BUTTON)
        if (label != null) attr(ATTR_ARIA_LABEL, label)
        onClick { onClick() }
    }) { Text(text) }
}

/**
 * What the main button says it will do: its accessible name and its tooltip. A stall the network caused says so
 * (en.json's `player.reconnecting`); any other stall is plain loading.
 */
internal fun primaryLabel(
    primary: PrimaryControl,
    phase: PlayerPhase,
): String = if (phase == PlayerPhase.Reconnecting) "Reconnecting…" else primaryActionLabel(primary)

private fun primaryActionLabel(primary: PrimaryControl): String =
    when (primary) {
        PrimaryControl.Pause -> "Pause"
        PrimaryControl.Play, PrimaryControl.TapToPlay -> "Play"
        PrimaryControl.Busy -> "Loading. Pause"
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
    extraClass: String,
    shown: Boolean = true,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("tport-skip", extraClass)
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr(ATTR_ARIA_LABEL, label)
        attr(ATTR_TITLE, label)
        // Hidden, it keeps its box (`.off` is `visibility:hidden`) and leaves the tab order and the
        // accessibility tree, so the main button beside it never moves.
        if (!shown) {
            classes("off")
            attr("aria-hidden", "true")
            attr("tabindex", "-1")
            disabled()
        }
        onClick { onClick() }
    }) {
        Icon(icon, size = TRANSPORT_ICON_SIZE)
        // aria-hidden: the button's own label already says "Skip back 10 seconds", and without this a
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
    startMs: Long = 0L,
    label: String = "Seek",
    showsRemaining: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = dragPositionMs ?: positionMs

    Span(attrs = { classes("mono", timeClass) }) { Text(formatElapsed(shownPositionMs)) }

    // A place in the story, never in a file: the panel's range spans the whole book, the bar's the chapter
    // ([startMs] is where that span begins in the book, so a release seeks book-relative either way).
    Input(type = InputType.Range) {
        classes(scrubClass)
        attr("min", "0")
        // A zero-length range collapses the thumb onto the track and reports every drag as 0.
        attr("max", durationMs.coerceAtLeast(1L).toString())
        // Without this the range keeps its default step of 1 — one MILLISECOND per arrow key,
        // so a keyboard listener would need thirty thousand presses to skip half a minute.
        attr("step", STEP_MS.toString())
        attr(ATTR_ARIA_LABEL, label)
        // The implicit `aria-valuenow` is the raw millisecond count, which a screen reader
        // reads out as "four hundred and twenty thousand". Same courtesy the play button gets.
        // "3:30 of 42:00", not just "3:30": where you are only means something against the whole.
        attr("aria-valuetext", "${formatElapsed(shownPositionMs)} of ${formatElapsed(durationMs)}")
        value(shownPositionMs.toString())
        onInput { event -> dragPositionMs = scrubbedMs(event.target.value) }
        onChange { event ->
            val target = scrubbedMs(event.target.value) ?: positionMs
            dragPositionMs = null
            onSeek(startMs + target)
        }
    }

    // The bar counts down what is left of the chapter; the panel shows the book's length.
    val trailing =
        if (showsRemaining) "\u2212" + formatElapsed((durationMs - shownPositionMs).coerceAtLeast(0L)) else formatElapsed(durationMs)
    Span(attrs = { classes("mono", timeClass) }) { Text(trailing) }
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

private const val VALUE_BUTTON = "button"

/** 2× the bar's 56px cover. */
private const val BAR_COVER_RUNG = 112

private const val BAR_COVER_RADIUS = 6

private const val PERCENT = 100

/** A speed is carried as hundredths so the ladder's quarter steps stay exact in integer maths. */
private const val HUNDREDTHS = 100

private const val TENTHS = 10
