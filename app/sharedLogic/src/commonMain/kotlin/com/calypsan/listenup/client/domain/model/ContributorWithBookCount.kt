package com.calypsan.listenup.client.domain.model

/**
 * A contributor with how many books they have in a role, and up to two of those books' ids, newest added first, for
 * the covers a wide Library row shows (spec §3.1.8 c7). Ids rather than covers: every client resolves a cover from the
 * id.
 */
data class ContributorWithBookCount(
    val contributor: Contributor,
    val bookCount: Int,
    val previewBookIds: List<String> = emptyList(),
)
