package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolls so the item at [index] sits in the middle of the viewport (spec §3.11's opening rule), or as near as the
 * list's ends allow. Reads the item's laid-out size, so it is right for any row height. Call it once the list has laid
 * out (§9.2: centring before the container is open lands on the wrong place). Used by the phone chapter sheet (PR 4)
 * and the wide chapter panels (PR 5).
 */
internal suspend fun LazyListState.centreOn(index: Int) {
    // scrollToItem remeasures before it returns, so the layout below already has the item at the top.
    scrollToItem(index)
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCentre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    val itemCentre = item.offset + item.size / 2
    scrollBy((itemCentre - viewportCentre).toFloat())
}
