package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.SharePermission

/**
 * Domain model for a single share grant on a collection.
 *
 * Surfaces one `(collection, user, permission)` triple so a collection owner can
 * inspect and revoke individual shares. Mirrors the wire
 * [com.calypsan.listenup.api.dto.CollectionShareDto].
 *
 * @property id Stable identifier for this share record.
 * @property collectionId The collection that was shared.
 * @property sharedWithUserId The user who received access.
 * @property permission The permission level granted to the recipient.
 */
data class CollectionShare(
    val id: String,
    val collectionId: String,
    val sharedWithUserId: String,
    val permission: SharePermission,
)
