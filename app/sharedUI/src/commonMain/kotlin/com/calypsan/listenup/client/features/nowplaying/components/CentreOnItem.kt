package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState

/** Rounding slack for "fully in view", as the web page's `EDGE_PX`: half a pixel either way. */
private const val EDGE_SLACK_PX = 0.5f

/**
 * Whether the item at [index] is laid out wholly inside the viewport: §3.11's one input to `ChapterListFollow`
 * ("follow only when the old current row was fully visible"). A row cut by either edge, or not laid out, is not.
 */
internal fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset + EDGE_SLACK_PX >= layout.viewportStartOffset &&
        item.offset + item.size - EDGE_SLACK_PX <= layout.viewportEndOffset
}

/**
 * Scrolls so the item at [index] sits in the middle of the viewport (spec §3.11's opening rule), or as near as the
 * list's ends allow. Reads the item's laid-out size, so it is right for any row height. Call it once the list has laid
 * out (§9.2: centring before the container is open lands on the wrong place).
 *
 * Used by the phone chapter sheet (a jump, PR 4) and the wide chapter panel (a jump on open, a glide on follow, PR 5).
 * With [animate] the list glides there on the scroll's default spring, which Compose's motion scale cuts to nothing
 * when the system's "Remove animations" is on (§8).
 */
internal suspend fun LazyListState.centreOn(
    index: Int,
    animate: Boolean = false,
) {
    if (!animate) {
        // scrollToItem remeasures before it returns, so the layout below already has the item at the top.
        scrollToItem(index)
        layoutInfo.centreOffsetOf(index)?.let { scrollBy(it) }
        return
    }
    // A row that isn't laid out has no centre to measure yet: bring it in first, then glide the rest of the way.
    if (layoutInfo.centreOffsetOf(index) == null) animateScrollToItem(index)
    layoutInfo.centreOffsetOf(index)?.let { animateScrollBy(it) }
}

/** How far to scroll so the laid-out item at [index] is centred, or null when it is not laid out. */
private fun LazyListLayoutInfo.centreOffsetOf(index: Int): Float? {
    val item = visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val viewportCentre = (viewportStartOffset + viewportEndOffset) / 2
    return (item.offset + item.size / 2 - viewportCentre).toFloat()
}
