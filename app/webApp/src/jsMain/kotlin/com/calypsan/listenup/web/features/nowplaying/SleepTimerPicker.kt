package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.SleepTimerChoices
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * Stop listening at a time you choose, without having to be awake for it (§5.5.2, PT1–PT8, `Sheets-WebPhone`).
 *
 * Three states, because a sleep timer is three different questions:
 * - nothing running asks *when*: End of chapter first and largest, in the sleep gold, saying which chapter and how long
 *   (content time, open question 2); then the durations; then a custom number of minutes (C3: 1–720, any whole minute,
 *   since a typed number has no step to fight);
 * - something running asks *how much longer, and do you want more*: the wall-clock countdown ([countdownMs], from the
 *   shared `SleepCountdowns`), when it pauses by the browser's clock, a gold meter of what is left, +5 / +10 / +15 for a
 *   duration (C6), and Cancel timer, which keeps the panel open on the choices (S13);
 * - a fade under way is the fade card's words, with Keep listening, which cancels the timer and puts full volume back
 *   (PT7).
 *
 * The ladders and the two rules come from the shared [SleepTimerChoices], so the browser offers exactly what the phone
 * does.
 */
@Composable
internal fun SleepTimerPicker(
    open: Boolean,
    state: SleepTimerState,
    /** The current chapter: whether End of chapter is offered, and the chapter its lines name. */
    chapter: PlayerChapter,
    /** Content time left in the chapter: "Pauses after Bridge Four · in 8m". */
    chapterRemainingMs: Long,
    /** The running timer's wall-clock countdown (`SleepCountdowns.remainingMs`), or null with none running. */
    countdownMs: Long?,
    /** What the gold meter draws (`SleepCountdowns.meterFraction`): the share still to run. */
    meterFraction: Float,
    /** Now, by the browser's clock, for "Pauses at". */
    nowMs: Long,
    placement: PanelPlacement,
    onSet: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
    onExtend: (Int) -> Unit,
    onKeepListening: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanel(
        open = open,
        title = NowPlayingWords.SLEEP_TIMER,
        panelClass = "sleep-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        when (state) {
            is SleepTimerState.Inactive -> {
                IdleOptions(chapter = chapter, chapterRemainingMs = chapterRemainingMs, onSet = onSet)
            }

            is SleepTimerState.Active -> {
                RunningTimer(
                    state = state,
                    chapter = chapter,
                    chapterRemainingMs = chapterRemainingMs,
                    countdownMs = countdownMs ?: state.remainingMs,
                    meterFraction = meterFraction,
                    nowMs = nowMs,
                    onExtend = onExtend,
                    onCancel = onCancel,
                )
            }

            is SleepTimerState.FadingOut -> {
                SleepFadingCard { action -> if (action == PlayerCardAction.KeepListening) onKeepListening() }
            }
        }
    }
}

/** Nothing is running: End of chapter, the durations, a custom number of minutes. */
@Composable
private fun IdleOptions(
    chapter: PlayerChapter,
    chapterRemainingMs: Long,
    onSet: (SleepTimerMode) -> Unit,
) {
    // Only when the book has marks (PT1). End-of-chapter waits to be told a chapter turned over, and a whole-book
    // "chapter" ends with the book, so the timer would quietly do nothing until then.
    if (SleepTimerChoices.offersEndOfChapter(chapter)) {
        DomButton(attrs = {
            classes("sleep-eoc")
            attr("type", VALUE_BUTTON)
            onClick { onSet(SleepTimerMode.EndOfChapter) }
        }) {
            Span(attrs = { classes("sleep-eoc-t") }) { Text(NowPlayingWords.END_OF_CHAPTER) }
            Span(attrs = { classes("sleep-eoc-s") }) {
                Text(NowPlayingWords.endOfChapterAfter(chapterName(chapter), chapterRemainingMs))
            }
        }
    }

    Div(attrs = {
        classes("sleep-opts")
        attr("role", "group")
        attr("aria-label", NowPlayingWords.DURATIONS)
    }) {
        SleepTimerChoices.DURATION_MINUTES.forEach { minutes ->
            val mode = SleepTimerMode.Duration(minutes)
            DomButton(attrs = {
                classes("sleep-opt")
                attr("type", VALUE_BUTTON)
                onClick { onSet(mode) }
            }) {
                Text(mode.label)
            }
        }
    }

    CustomMinutes(onStart = { minutes -> onSet(SleepTimerMode.Duration(minutes)) })

    P(attrs = { classes("panel-note") }) { Text(NowPlayingWords.FADES_OUT) }
}

/**
 * "Custom (minutes)" and Start (PT8): any whole minute, clamped into the shared bounds by [SleepTimerChoices.parseCustom];
 * Start is disabled while the field holds nothing it can read.
 */
@Composable
private fun CustomMinutes(onStart: (Int) -> Unit) {
    var raw by remember { mutableStateOf(DEFAULT_CUSTOM) }
    val minutes = SleepTimerChoices.parseCustom(raw)
    Div(attrs = { classes("sleep-custom") }) {
        Label(forId = CUSTOM_FIELD_ID, attrs = { classes("sleep-custom-l") }) {
            Text(NowPlayingWords.CUSTOM_MINUTES)
            Input(type = InputType.Number) {
                classes("sleep-custom-in")
                id(CUSTOM_FIELD_ID)
                attr("min", SleepTimerChoices.CUSTOM_MIN_MINUTES.toString())
                attr("max", CUSTOM_FIELD_MAX.toString())
                attr("step", "1")
                attr("inputmode", "numeric")
                value(raw)
                onInput { event -> raw = event.target.value }
            }
        }
        Button(
            kind = ButtonKind.Primary,
            enabled = minutes != null,
            onClick = { minutes?.let(onStart) },
            attrs = { classes("sleep-start") },
        ) { Text(NowPlayingWords.START) }
    }
}

/** A timer is running: how long is left, when it pauses, the gold meter, more time, and Cancel. */
@Composable
private fun RunningTimer(
    state: SleepTimerState.Active,
    chapter: PlayerChapter,
    chapterRemainingMs: Long,
    countdownMs: Long,
    meterFraction: Float,
    nowMs: Long,
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val mode = state.mode
    // `role="timer"` with `aria-live="off"`: the countdown re-renders every second, and a live region here would have a
    // screen reader interrupt the book to read a new number 900 times. The role still lets someone ask for it.
    P(attrs = {
        classes("sleep-left")
        attr("role", "timer")
        attr("aria-live", "off")
    }) {
        Text(NowPlayingWords.countdown(countdownMs))
    }

    P(attrs = { classes("sleep-pauses") }) {
        Text(
            when (mode) {
                is SleepTimerMode.Duration -> NowPlayingWords.pausesAt(nowMs + countdownMs, timerMinutes(state, mode))
                SleepTimerMode.EndOfChapter -> NowPlayingWords.untilEndOf(chapterName(chapter), chapterRemainingMs)
            },
        )
    }

    // What is LEFT, in gold (§3.9): "23:41 of 30:00 remaining". An end-of-chapter timer's share is the chapter's.
    val meterLabel =
        when (mode) {
            is SleepTimerMode.Duration -> NowPlayingWords.meterLabel(countdownMs, state.totalMs)
            SleepTimerMode.EndOfChapter -> NowPlayingWords.meterLabel(chapterRemainingMs, chapter.durationMs)
        }
    Div(attrs = {
        classes("sleep-meter")
        attr("role", "img")
        attr("aria-label", meterLabel)
    }) {
        Div(attrs = {
            classes("sleep-meter-fill")
            style { width((meterFraction.coerceIn(0f, 1f) * PERCENT).percent) }
        })
    }

    // Extending is arithmetic on a countdown, so it is offered only for a duration (C6): the shared timer ignores an
    // extend on an end-of-chapter timer, so "+5 min" there would be a button whose press changes nothing.
    if (SleepTimerChoices.canExtend(state)) {
        Div(attrs = {
            classes("sleep-opts", "sleep-extend")
            attr("role", "group")
            attr("aria-label", NowPlayingWords.ADD_TIME)
        }) {
            SleepTimerChoices.EXTEND_MINUTES.forEach { minutes ->
                DomButton(attrs = {
                    classes("sleep-opt")
                    attr("type", VALUE_BUTTON)
                    onClick { onExtend(minutes) }
                }) {
                    Text(NowPlayingWords.extend(minutes))
                }
            }
        }
    }

    // Keeps the panel open (S13): the choices come back, ready for another timer.
    DomButton(attrs = {
        classes("sleep-cancel")
        attr("type", VALUE_BUTTON)
        onClick { onCancel() }
    }) { Text(NowPlayingWords.CANCEL_TIMER) }
}

/** "30 min timer": the minutes the timer now runs for, extensions included, or the mode's own when it has no total. */
private fun timerMinutes(
    state: SleepTimerState.Active,
    mode: SleepTimerMode.Duration,
): Int = if (state.totalMs > 0) (state.totalMs / MS_PER_MINUTE).toInt() else mode.minutes

/** The chapter as its lines name it: its own title, or "Chapter 6" for an untitled one. */
private fun chapterName(chapter: PlayerChapter): String =
    chapter.title.ifBlank { NowPlayingWords.counter(chapter.number, chapter.total) }

/** Named once: several buttons in this file set the same attribute. */
private const val VALUE_BUTTON = "button"

private const val CUSTOM_FIELD_ID = "sleep-custom-minutes"

/** Where the custom field starts: the shared stepper's default (the boards). */
private val DEFAULT_CUSTOM = SleepTimerChoices.CUSTOM_DEFAULT_MINUTES.toString()

/**
 * The custom field's ceiling (open question 3, C3's default): the spec's shared bound, 720 minutes, which overrules the
 * web board's `max=600`. One constant, so the call is easy to change.
 */
private const val CUSTOM_FIELD_MAX = SleepTimerChoices.CUSTOM_MAX_MINUTES

private const val MS_PER_MINUTE = 60_000L

private const val PERCENT = 100
