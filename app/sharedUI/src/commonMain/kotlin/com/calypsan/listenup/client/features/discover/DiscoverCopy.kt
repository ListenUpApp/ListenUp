package com.calypsan.listenup.client.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_listening_count_both
import listenup.composeapp.generated.resources.discover_listening_count_live
import listenup.composeapp.generated.resources.discover_listening_count_recent
import listenup.composeapp.generated.resources.discover_pick_series_line
import listenup.composeapp.generated.resources.discover_pick_standalone
import listenup.composeapp.generated.resources.discover_recently_added_when
import org.jetbrains.compose.resources.stringResource

/** "2 listening now · 3 more in the last two weeks"; null when nobody is shown. */
@Composable
internal fun listeningCountLine(
    live: Int,
    recent: Int,
): String? =
    when {
        live == 0 && recent == 0 -> null
        recent == 0 -> stringResource(Res.string.discover_listening_count_live, live)
        live == 0 -> stringResource(Res.string.discover_listening_count_recent, recent)
        else -> stringResource(Res.string.discover_listening_count_both, live, recent)
    }

/** "Book 1 of Mistborn", the bare series name when unnumbered, or "Standalone" (plan C10). */
@Composable
internal fun pickSeriesLine(
    series: String?,
    sequence: String?,
): String =
    when {
        series == null -> stringResource(Res.string.discover_pick_standalone)
        sequence == null -> series
        else -> stringResource(Res.string.discover_pick_series_line, sequence, series)
    }

/** "Added 2 days ago", in the relative vocabulary the app already speaks (plan C9). */
@Composable
internal fun addedLine(
    createdAtMs: Long,
    nowMs: Long,
): String = stringResource(Res.string.discover_recently_added_when, relativeLastActiveInSentence(createdAtMs, nowMs))
