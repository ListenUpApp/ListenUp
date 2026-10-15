package com.calypsan.listenup.client.domain.model

/**
 * What a new listener's Home shows of the library (spec §2.3): a few of the newest covers. It deliberately carries no
 * count of the library; the welcome invites, it does not tally.
 *
 * @property covers the newest playable books that have a cover, newest first.
 */
data class LibraryShowcase(
    val covers: List<ShelfCover>,
)
