package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_scrubber_a11y
import listenup.composeapp.generated.resources.player_scrubber_value_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val TrackHeight = 16.dp
private val HandleSize = DpSize(width = 4.dp, height = 44.dp)

/**
 * The player's scrubber (spec §6): the M3 Expressive slider with a 16dp `primary` active track, a 4dp handle with
 * gaps, an inactive `surfaceContainerHighest` track and its stop dot, over elapsed · −remaining.
 * Holds its own drag and calls [onSeek] once, on release, so a position tick never yanks the thumb and a drag never
 * issues a seek per frame (m3.material.io/components/sliders/guidelines).
 *
 * [fraction], [elapsedMs] and [durationMs] are the chapter's (§3.2: everything that moves is chapter-scoped).
 * [timesBeside] puts the times either side of the track on one row instead of under it, for a one-row toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackSlider(
    fraction: Float,
    elapsedMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    timesBeside: Boolean = false,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val shownFraction = drag ?: fraction
    val shownElapsed = drag?.let { (it * durationMs).toLong() } ?: elapsedMs
    val elapsed = shownElapsed.milliseconds.formatPlaybackTime()
    val total = durationMs.milliseconds.formatPlaybackTime()
    val remaining = "-" + (durationMs - shownElapsed).coerceAtLeast(0).milliseconds.formatPlaybackTime()
    val label = stringResource(Res.string.player_scrubber_a11y)
    val value = stringResource(Res.string.player_scrubber_value_a11y, elapsed, total)
    val colors =
        SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            thumbColor = MaterialTheme.colorScheme.primary,
        )
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val track: @Composable (Modifier) -> Unit = { trackModifier ->
        Slider(
            value = shownFraction,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let(onSeek)
                drag = null
            },
            enabled = enabled,
            colors = colors,
            interactionSource = interaction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = colors,
                    enabled = enabled,
                    thumbSize = HandleSize,
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(TrackHeight),
                    colors = colors,
                    enabled = enabled,
                )
            },
            modifier =
                trackModifier.semantics {
                    contentDescription = label
                    stateDescription = value
                },
        )
    }
    if (timesBeside) {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(elapsed, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            track(Modifier.weight(1f))
            Text(remaining, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        track(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(elapsed, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(remaining, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A playback position or length as a clock: "12:04", or "1:02:03" past an hour. */
fun Duration.formatPlaybackTime(): String {
    val hours = inWholeHours
    val minutes = inWholeMinutes % 60
    val seconds = inWholeSeconds % 60

    // ASCII digits in every locale, like DurationFormatter, so one screen never mixes two numeral
    // systems; and plain padding rather than String.format, which commonMain cannot reach.
    val clock = "$minutes:${seconds.toString().padStart(2, '0')}"
    return if (hours >
        0
    ) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        clock
    }
}
