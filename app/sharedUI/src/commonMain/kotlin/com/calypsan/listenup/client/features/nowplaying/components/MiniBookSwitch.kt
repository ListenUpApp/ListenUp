package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/**
 * The mini player's content, moved out and back in when the book changes (Simon, 2026-10-14), so a switch reads as
 * deliberate and never as a one-frame flicker. The old book drops out on the fast spatial and effects specs while the
 * new one rises in on the default ones (`MaterialTheme.motionScheme`), clipped to the bar. Keyed on the book id: a
 * phase or chapter change within the same book redraws in place.
 *
 * The caller's container stays put around this, so the bar itself never leaves or shows an empty frame. Under Remove
 * animations ([LocalReduceMotion]) the new book replaces the old in the same frame.
 */
@Composable
internal fun MiniBookSwitch(
    state: PlayerUiState.Loaded,
    modifier: Modifier = Modifier,
    content: @Composable (PlayerUiState.Loaded) -> Unit,
) {
    if (LocalReduceMotion.current) {
        Box(modifier, contentAlignment = Alignment.CenterStart) { content(state) }
        return
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        contentKey = { it.book.id },
        transitionSpec = { bookSwitch(motion) },
        label = "mini player book switch",
    ) { shown -> content(shown) }
}

/** Out downwards, in from below, clipped to the bar (the default size transform): one book hands over to the next. */
private fun bookSwitch(motion: MotionScheme): ContentTransform =
    (
        slideInVertically(motion.defaultSpatialSpec()) { height -> height / 2 } +
            fadeIn(motion.defaultEffectsSpec())
    ).togetherWith(
        slideOutVertically(motion.fastSpatialSpec()) { height -> height / 2 } +
            fadeOut(motion.fastEffectsSpec()),
    )
