package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One in-flight emoji reaction rising over the Now Playing screen. */
data class FloatingReaction(
    val id: Long,
    val emoji: String,
)

// Total rise + fade duration for one reaction.
private const val RISE_DURATION_MS = 1_800
private val RISE_DISTANCE = 220.dp

/**
 * Floating-emoji overlay for incoming Campfire reactions (co-listening design spec §5,
 * campfire implementation plan Task 10) — each [FloatingReaction] rises and fades over the Now
 * Playing screen, then calls [onFinished] so the caller can drop it from the backing list. Purely
 * decorative; never folded into session state (see [com.calypsan.listenup.client.presentation.campfire.CampfireScreenEvent.ReactionReceived]'s KDoc).
 */
@Composable
fun CampfireReactionOverlay(
    reactions: List<FloatingReaction>,
    onFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        reactions.forEach { reaction ->
            RisingReaction(
                emoji = reaction.emoji,
                onFinished = { onFinished(reaction.id) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun RisingReaction(
    emoji: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        offsetY.animateTo(-RISE_DISTANCE.value, tween(RISE_DURATION_MS, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        alpha.animateTo(0f, tween(RISE_DURATION_MS, easing = LinearEasing))
        onFinished()
    }

    Text(
        text = emoji,
        fontSize = 40.sp,
        textAlign = TextAlign.Center,
        modifier =
            modifier.graphicsLayer {
                translationY = offsetY.value
                this.alpha = alpha.value
            },
    )
}
