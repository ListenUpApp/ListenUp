package com.calypsan.listenup.client.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Robolectric window qualifiers for the widths a responsive screen is pinned at: a landscape tablet,
 * where the wide layout has to use the width; a small tablet, where a side panel would starve the
 * content but one column would waste the width; and a phone, where the single column must stay
 * exactly as it was.
 */
object Windows {
    const val TABLET = "w1280dp-h800dp"
    const val PHONE = "w400dp-h800dp"

    /** A small tablet or an unfolded foldable: medium width, below the side-panel breakpoint. */
    const val SMALL_TABLET = "w700dp-h1000dp"
}

/**
 * Asserts [first] and [second] sit side by side — two columns, not one: [second] starts to the right
 * of where [first] ends, and their vertical extents overlap, so they share a row.
 */
fun assertSideBySide(
    first: SemanticsNodeInteraction,
    second: SemanticsNodeInteraction,
) {
    val a = first.getUnclippedBoundsInRoot()
    val b = second.getUnclippedBoundsInRoot()
    b.left shouldBeGreaterThanOrEqualTo a.right
    b.top shouldBeLessThan a.bottom
    a.top shouldBeLessThan b.bottom
}

/**
 * Asserts [upper] and [lower] are stacked in one column — the same left edge, [lower] below [upper].
 */
fun assertStacked(
    upper: SemanticsNodeInteraction,
    lower: SemanticsNodeInteraction,
) {
    val a = upper.getUnclippedBoundsInRoot()
    val b = lower.getUnclippedBoundsInRoot()
    b.left shouldBe a.left
    b.top shouldBeGreaterThan a.top
}

/**
 * Asserts [node] starts right of where [of] ends — it lives in a column beside [of]'s, whatever their
 * heights. For a wide layout's action, which sits lower in its pane than anything in the side panel.
 */
fun assertRightOf(
    node: SemanticsNodeInteraction,
    of: SemanticsNodeInteraction,
) {
    node.getUnclippedBoundsInRoot().left shouldBeGreaterThanOrEqualTo of.getUnclippedBoundsInRoot().right
}
