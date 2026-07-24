package com.calypsan.listenup.client.presentation.search

/**
 * Per-type display caps for the main search results view.
 *
 * The federated search returns hits for every type at once, so without a cap a fuzzy book match
 * would bury the People/Series groups far down the scroll. Each group renders at most this many
 * hits inline; when more exist, the UI offers a "See all" action that opens the full single-type
 * page. Tags are intentionally absent — they render inline as compact pills and need no cap.
 *
 * This is the single source of truth for the caps; both the Android and iOS overlays read it so the
 * two platforms stay in lockstep.
 */
object SearchResultCaps {
    /** Max book hits shown inline in the main results view. */
    const val BOOK = 4

    /** Max contributor hits shown inline in the main results view. */
    const val CONTRIBUTOR = 3

    /** Max series hits shown inline in the main results view. */
    const val SERIES = 3
}
