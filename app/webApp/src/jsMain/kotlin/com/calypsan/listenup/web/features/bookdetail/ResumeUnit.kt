package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.FinishedSpan
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.percentOf
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.js.Date
import kotlin.js.dateLocaleOptions
import kotlin.time.Duration.Companion.milliseconds

/**
 * Book Detail's progress unit on the web (spec §2.9): "Ch. 6 · Bridge Four" with "11% · 40h 31m left" over the bar
 * while in progress; "Finished · 12 Feb – 3 Mar 2026" once finished; nothing before it starts. It sits directly
 * above the action row, so where you are reads before what to do next.
 */
@Composable
internal fun ResumeUnit(ready: BookDetailUiState.Ready) {
    when (ready.readingStage) {
        ReadingStage.NotStarted -> {
            Unit
        }

        ReadingStage.Finished -> {
            ready.finishedSpan?.let { span ->
                Div(attrs = { classes("bd-unit") }) {
                    Span(attrs = { classes("bd-unit-finished") }) { Text(finishedSpanText(span)) }
                }
            }
        }

        ReadingStage.InProgress -> {
            val fraction = ready.progress ?: 0f
            val left = ready.timeRemainingMs?.let { DurationFormatter.hoursMinutesAlways(it.milliseconds) }
            Div(attrs = { classes("bd-unit") }) {
                Div(attrs = { classes("bd-unit-row") }) {
                    Span(attrs = { classes("bd-unit-ch") }) {
                        ready.currentChapter?.let { Text("Ch. ${it.number} · ${it.title}") }
                    }
                    Span(attrs = { classes("bd-unit-left") }) {
                        Text(listOfNotNull("${percentOf(fraction)}%", left?.let { "$it left" }).joinToString(" · "))
                    }
                }
                ProgressBar(value = fraction, label = "Listening progress")
            }
        }
    }
}

/** "Finished · 12 Feb – 3 Mar 2026", or "Finished · 3 Mar 2026" when the start was never recorded. */
internal fun finishedSpanText(span: FinishedSpan): String {
    val finished = day(span.finishedAtMs, withYear = true)
    return span.startedAtMs?.let { "Finished · ${day(it, withYear = false)} – $finished" } ?: "Finished · $finished"
}

/** A calendar day in the reader's own locale ("12 Feb 2026", or "Feb 12, 2026" in the US). */
private fun day(
    epochMs: Long,
    withYear: Boolean,
): String =
    Date(epochMs.toDouble()).toLocaleDateString(
        "default",
        dateLocaleOptions {
            day = "numeric"
            month = "short"
            if (withYear) year = "numeric"
        },
    )
