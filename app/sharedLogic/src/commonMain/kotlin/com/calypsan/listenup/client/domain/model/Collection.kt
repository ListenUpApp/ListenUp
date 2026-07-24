package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.SharePermission

/**
 * Domain model for a collection — an admin-owned, library-scoped grouping of books
 * with an explicit per-user ACL.
 *
 * Mirrors the wire [com.calypsan.listenup.api.dto.CollectionSummary]: [bookCount] is
 * computed at read time (JOIN-derived on the client; query-time on the server),
 * [callerPermission] is the effective permission of the current user, and [isOwner]
 * drives owner-only UI affordances (rename, delete, share).
 *
 * @property id Stable identifier.
 * @property name Display name.
 * @property ownerId User who owns (created) this collection.
 * @property isInbox Whether this is the auto-created inbox collection (not deletable).
 * @property isSystem Whether this is a server-managed system collection (All Books or Inbox) — not renameable/deletable.
 * @property bookCount Number of live books currently in this collection.
 * @property callerPermission Effective permission of the current user.
 * @property isOwner Whether the current user owns this collection.
 */
data class Collection(
    val id: String,
    val name: String,
    val ownerId: String,
    val isInbox: Boolean,
    val isSystem: Boolean,
    val bookCount: Int,
    val callerPermission: SharePermission,
    val isOwner: Boolean,
)
