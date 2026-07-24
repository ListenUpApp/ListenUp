package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a contributor with their book count.
 *
 * Used for library views that display contributors (authors, narrators)
 * with the number of books they're associated with.
 */
data class ContributorWithBookCount(
    val contributor: Contributor,
    val bookCount: Int,
)
