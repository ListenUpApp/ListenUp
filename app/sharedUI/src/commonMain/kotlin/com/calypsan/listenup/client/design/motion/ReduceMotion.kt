package com.calypsan.listenup.client.design.motion

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the person has asked for animations to be removed (Android's "Remove animations", which
 * sets the animator duration scale to zero). False where a platform has no such setting.
 *
 * Tweens and springs need nothing from this: Compose's `MotionDurationScale` already finishes them
 * instantly. It exists for the motion that scale cannot stop — ambient loops, infinite transitions
 * and frame-driven drifts — which must hold still instead.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Whether a screen reader is exploring the screen by touch (TalkBack). Content that moves on its own
 * pulls the reader's focus out from under the finger, so self-advancing motion pauses while it is on.
 */
val LocalTouchExplorationActive = staticCompositionLocalOf { false }

/**
 * An ambient loop between [initialValue] and [targetValue], or [restValue] held still while
 * [LocalReduceMotion] is on.
 *
 * Use it for decorative, never-ending motion (a spinner's rotation, a pulse), where the rest pose
 * still reads correctly on its own. Anything that carries meaning should be a finite animation,
 * which the system scale already handles.
 */
@Composable
fun rememberAmbientLoop(
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    restValue: Float,
    label: String,
): State<Float> {
    if (LocalReduceMotion.current) {
        return remember(restValue) { mutableFloatStateOf(restValue) }
    }
    return rememberInfiniteTransition(label = label).animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = label,
    )
}
