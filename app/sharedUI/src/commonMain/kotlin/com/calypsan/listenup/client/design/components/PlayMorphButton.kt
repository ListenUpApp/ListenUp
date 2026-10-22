package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics

private val PlayingCorner = 28.dp
private val PressedCorner = 20.dp
private const val INDICATOR_FRACTION = 0.6f
private const val GLYPH_FRACTION = 0.42f

/**
 * Now Playing's one main button (spec P14, §6): a 96dp filled toggle icon button in `primary` that is a 28dp-corner
 * square while playing and a circle while paused, morphing on the theme's fast spatial spring
 * (m3.material.io/components/icon-buttons: "changing shape when selected"). While [isBusy] it shows the contained
 * loading indicator and a press still reaches [onClick], so a stall can be paused.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayMorphButton(
    isPlaying: Boolean,
    isBusy: Boolean,
    playLabel: String,
    pauseLabel: String,
    busyLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val haptics = LocalHaptics.current
    val colors = MaterialTheme.colorScheme
    val label =
        when {
            isBusy -> busyLabel
            isPlaying -> pauseLabel
            else -> playLabel
        }
    FilledIconToggleButton(
        checked = isPlaying,
        onCheckedChange = {
            haptics.toggle(on = !isPlaying)
            onClick()
        },
        shapes =
            IconButtonDefaults.toggleableShapes(
                shape = CircleShape,
                pressedShape = RoundedCornerShape(PressedCorner),
                checkedShape = RoundedCornerShape(PlayingCorner),
            ),
        colors =
            IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                checkedContainerColor = colors.primary,
                checkedContentColor = colors.onPrimary,
            ),
        modifier = modifier.size(size).semantics { contentDescription = label },
    ) {
        if (isBusy) {
            ContainedLoadingIndicator(
                containerColor = colors.primaryContainer,
                indicatorColor = colors.onPrimaryContainer,
                modifier = Modifier.size(size * INDICATOR_FRACTION).clearAndSetSemantics {},
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(size * GLYPH_FRACTION),
            )
        }
    }
}
