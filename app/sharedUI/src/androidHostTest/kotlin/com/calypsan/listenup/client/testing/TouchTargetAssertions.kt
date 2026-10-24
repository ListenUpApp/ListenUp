package com.calypsan.listenup.client.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual

/** §8's minimum target: what every tappable control in the player keeps, measured as its touch bounds. */
val MinimumTarget: Dp = 48.dp

/**
 * Asserts this node's touch bounds — its laid-out size grown by Compose's minimum interactive size — are at least
 * [minimum] in both directions: the area a finger actually hits, which a 40dp button padded to 48dp passes.
 */
fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp = MinimumTarget): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val minimumPx = minimum.value * node.layoutInfo.density.density
    val bounds = node.touchBoundsInRoot
    withClue("touch width of ${node.config}") { bounds.width shouldBeGreaterThanOrEqual minimumPx }
    withClue("touch height of ${node.config}") { bounds.height shouldBeGreaterThanOrEqual minimumPx }
    return this
}
