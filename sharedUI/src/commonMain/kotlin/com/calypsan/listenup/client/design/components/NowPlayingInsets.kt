package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom space currently occupied by the floating now-playing bar, expressed as a
 * [WindowInsets] so [ListenUpScaffold] can union it with the system bars. Zero when no
 * mini-player is showing. Provided once at the authenticated navigation root by
 * [ProvideNowPlayingInsets]; the default is empty so screens shown outside that scope
 * (and previews) simply see no extra inset.
 *
 * Deliberately [compositionLocalOf], not [staticCompositionLocalOf]: the inset animates
 * per-frame while the bar glides, so read-tracked invalidation (only the actual consumers
 * recompose) is far cheaper than the whole-subtree recomposition `static` forces on every
 * change. The other design-system locals are `static` because their values rarely change;
 * this one does not.
 */
val LocalNowPlayingInsets: ProvidableCompositionLocal<WindowInsets> =
    compositionLocalOf { WindowInsets(0, 0, 0, 0) }

/**
 * The bottom clearance the mini-player requires, in dp. The bar's measured footprint when
 * it is visible, otherwise zero — so the space is reclaimed the instant playback stops.
 */
fun nowPlayingClearance(
    barVisible: Boolean,
    latchedFootprint: Dp,
): Dp = if (barVisible) latchedFootprint else 0.dp

/**
 * Latches the bar's full footprint. We only adopt a fresh measurement while the bar is
 * visible and non-empty; transient zero/partial sizes emitted as the bar animates out are
 * ignored, so the value stays stable for the next appearance.
 */
fun latchFootprint(
    current: Dp,
    measured: Dp,
    barVisible: Boolean,
): Dp = if (barVisible && measured > 0.dp) measured else current

/**
 * Publishes the animated mini-player inset to descendants via [LocalNowPlayingInsets].
 * [barVisible] gates presence; [latchedFootprint] is the bar's measured height. The inset
 * springs between zero and the footprint so docked content glides in step with the bar's
 * own slide-in/out rather than snapping.
 */
@Composable
fun ProvideNowPlayingInsets(
    barVisible: Boolean,
    latchedFootprint: Dp,
    content: @Composable () -> Unit,
) {
    val target = nowPlayingClearance(barVisible, latchedFootprint)
    val animated by animateDpAsState(
        targetValue = target,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "nowPlayingInset",
    )
    CompositionLocalProvider(
        LocalNowPlayingInsets provides WindowInsets(bottom = animated),
    ) {
        content()
    }
}
