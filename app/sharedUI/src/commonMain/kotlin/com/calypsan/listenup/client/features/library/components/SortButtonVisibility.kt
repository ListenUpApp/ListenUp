package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/** How close to the top, in pixels of the first item's scroll, still counts as "at the top". */
private const val AT_TOP_THRESHOLD_PX = 50

/**
 * Whether a list's floating sort button should show: near the top, while scrolling up, or at rest.
 *
 * The previous offset is the collector's own memory, kept outside any snapshot state. Writing it
 * from inside a `derivedStateOf` — as this used to — mutates state during the read that derives
 * from it, which Compose does not promise to evaluate once or in order.
 */
@Composable
internal fun rememberSortButtonVisibility(listState: LazyListState): State<Boolean> {
    val visible = remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var previousOffset = 0
        snapshotFlow {
            ScrollSample(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                isScrollInProgress = listState.isScrollInProgress,
            )
        }.collect { sample ->
            visible.value =
                sortButtonVisible(
                    firstVisibleIndex = sample.firstVisibleIndex,
                    offset = sample.offset,
                    previousOffset = previousOffset,
                    isScrollInProgress = sample.isScrollInProgress,
                )
            previousOffset = sample.offset
        }
    }
    return visible
}

/** One reading of the list's scroll position. */
private data class ScrollSample(
    val firstVisibleIndex: Int,
    val offset: Int,
    val isScrollInProgress: Boolean,
)

/**
 * The sort button's visibility rule: shown near the top, while the first item's offset shrinks
 * (scrolling up), or whenever the list is not being scrolled.
 */
internal fun sortButtonVisible(
    firstVisibleIndex: Int,
    offset: Int,
    previousOffset: Int,
    isScrollInProgress: Boolean,
): Boolean {
    val isAtTop = firstVisibleIndex == 0 && offset < AT_TOP_THRESHOLD_PX
    val isScrollingUp = offset < previousOffset
    return isAtTop || isScrollingUp || !isScrollInProgress
}
