package com.calypsan.listenup.client.features.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.motion.LocalReduceMotion

/**
 * The shell's current tab, switched with Material's fade-through: the tab being left fades out
 * quickly, and the new one fades in while growing from [FADE_THROUGH_INITIAL_SCALE]. Tabs are peers,
 * not a hierarchy, so nothing slides.
 *
 * Each tab keeps its saved state (scroll position, filters, anything in `rememberSaveable`) while
 * another is showing, so switching back returns to where the person left it.
 *
 * Under Remove animations the switch is an instant cut, as DESIGN.md asks.
 */
@Composable
internal fun ShellTabContent(
    currentDestination: ShellDestination,
    modifier: Modifier = Modifier,
    content: @Composable (ShellDestination) -> Unit,
) {
    val tabStates = rememberSaveableStateHolder()
    val reduceMotion = LocalReduceMotion.current
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = currentDestination,
        modifier = modifier,
        contentKey = { it.route },
        transitionSpec = {
            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None else fadeThrough(motion)
        },
        label = "shell tab",
    ) { destination ->
        tabStates.SaveableStateProvider(destination.route) {
            content(destination)
        }
    }
}

/** Material fade-through, in the theme's motion: a quick fade out, and a fade and grow in. */
internal fun fadeThrough(motion: MotionScheme): ContentTransform =
    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), FADE_THROUGH_INITIAL_SCALE))
        .togetherWith(fadeOut(motion.fastEffectsSpec()))

/** Where an incoming tab grows from in [fadeThrough], per Material's fade-through pattern. */
private const val FADE_THROUGH_INITIAL_SCALE = 0.92f
