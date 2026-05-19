package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a contributor role with book count.
 *
 * Used on contributor detail pages to group books by the contributor's
 * role (author, narrator, translator, etc.) with counts for each role.
 */
data class RoleWithBookCount(
    val role: String,
    val bookCount: Int,
)
