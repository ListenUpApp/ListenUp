package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.FinishedSpan
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.util.formatDate
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapter_label
import listenup.composeapp.generated.resources.book_detail_finished
import listenup.composeapp.generated.resources.book_detail_finished_on
import listenup.composeapp.generated.resources.book_detail_finished_span
import listenup.composeapp.generated.resources.book_detail_progress_a11y
import listenup.composeapp.generated.resources.book_time_left
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * Book Detail's progress unit (spec §2.9): one unit directly above the action row. In progress, the current chapter
 * and the time left, over a full-width linear line (rev 7: the wave is for the book playing now, not this page).
 * Finished, the dates it was read between. Not started, nothing.
 */
@Composable
internal fun ResumeUnit(
    readingStage: ReadingStage,
    currentChapter: CurrentChapter?,
    timeRemainingMs: Long?,
    progress: Float?,
    finishedSpan: FinishedSpan?,
    modifier: Modifier = Modifier,
) {
    when (readingStage) {
        // Not started: nothing above the actions but the stats (spec §2.9).
        ReadingStage.NotStarted -> {}

        ReadingStage.Finished -> {
            FinishedLine(span = finishedSpan, modifier = modifier)
        }

        ReadingStage.InProgress -> {
            InProgressUnit(
                currentChapter = currentChapter,
                timeRemainingMs = timeRemainingMs,
                progress = progress ?: 0f,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun InProgressUnit(
    currentChapter: CurrentChapter?,
    timeRemainingMs: Long?,
    progress: Float,
    modifier: Modifier,
) {
    val percent = (progress * PERCENT).toInt().coerceIn(0, PERCENT)
    val progressLabel = stringResource(Res.string.book_detail_progress_a11y, percent)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text =
                    currentChapter
                        ?.let { chapter ->
                            stringResource(Res.string.book_detail_chapter_label, chapter.number, chapter.title)
                        }.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            timeRemainingMs?.let { remainingMs ->
                Text(
                    text =
                        stringResource(
                            Res.string.book_time_left,
                            DurationFormatter.hoursMinutesAlways(remainingMs.milliseconds),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // M3 1.12's default draws the track gap and the stop indicator (rev 7's end dot); don't zero the gap.
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
        )
    }
}

/** "Finished · 12 Feb – 3 Mar 2026"; "Finished · 3 Mar 2026" with no recorded start; plain "Finished" with no date. */
@Composable
private fun FinishedLine(
    span: FinishedSpan?,
    modifier: Modifier,
) {
    val startedAtMs = span?.startedAtMs
    val text =
        when {
            span == null -> {
                stringResource(Res.string.book_detail_finished)
            }

            startedAtMs == null -> {
                stringResource(Res.string.book_detail_finished_on, formatBookDay(span.finishedAtMs))
            }

            else -> {
                stringResource(
                    Res.string.book_detail_finished_span,
                    formatBookDay(startedAtMs, withYear = false),
                    formatBookDay(span.finishedAtMs),
                )
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/**
 * A calendar day as Book Detail prints it ("12 Feb 2026", or "12 Feb" without the year), in the device's locale
 * through the platform formatter. Shared by the finished span and the Details "Added" row.
 */
internal fun formatBookDay(
    epochMs: Long,
    withYear: Boolean = true,
): String = formatDate(epochMs, if (withYear) "d MMM yyyy" else "d MMM")

private const val PERCENT = 100
