package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * A book's cover colours (Book Detail spec §2.3, §4.4): the hero wash, the progress track and the harmonised action,
 * derived from the cover by one shared rule. Bound on iOS and in the browser only.
 *
 * `null` always means neutral: no readable cover, or a cover with no colour that sits apart from the coral (a
 * near-coral, grey or near-black cover). Callers keep their own neutral surface for it.
 */
interface CoverThemeRepository {
    /**
     * The cover's themes if this process has already read this [coverHash], else `null`. Synchronous and cheap, so a
     * revisit paints its colours in the first frame with no crossfade.
     */
    fun cached(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): CoverThemes?

    /**
     * Emits the cover's themes once: from memory when known, else after decoding a 64-px sample off the main thread.
     * Emits `null` for a cover that is unreadable (not yet downloaded, offline, unauthorised) or has no wash.
     */
    fun observe(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): Flow<CoverThemes?>
}
