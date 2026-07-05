package com.calypsan.listenup.server.api

/**
 * Sentinel owner id for server-managed system collections (ALL_BOOKS, INBOX).
 *
 * System collections are created at library bootstrap — before any admin user exists — so their
 * owner cannot be a real user id. This fixed string serves as the owner column value for all
 * system collections. It is never inserted into `users.id`; the `owner_id` column in
 * [com.calypsan.listenup.server.db.CollectionsTable] is a plain text column (not a foreign key)
 * specifically to allow this sentinel.
 *
 * [com.calypsan.listenup.server.api.CollectionAccessPolicy] grants owner-write via
 * `coll.ownerId == userId`; with this sentinel no real user matches, so the owner branch is
 * correctly inert for system collections. Admins reach them via the god-view null-filter path.
 */
internal const val SYSTEM_OWNER_ID = "system"

/**
 * The per-library system collections the server materialises lazily on first need.
 *
 * Identified by the server-only `collections.type` column (never on the wire). Member-facing
 * sync uses [com.calypsan.listenup.api.sync.CollectionSyncPayload.isInbox] / the access layer;
 * `type` is a server-internal discriminator that distinguishes the [ALL_BOOKS] substrate — the
 * exclusive everyone-collection that holds a book **iff** it is in no other (non-system) collection —
 * from the quarantine [INBOX]. Both are exclusive with regular collections. The enum name is the
 * persisted column value.
 */
internal enum class SystemCollectionType {
    ALL_BOOKS,
    INBOX,
}

/**
 * Persisted column value for the ALL_BOOKS system collection type.
 *
 * Equals [SystemCollectionType.ALL_BOOKS].name. Declared as a val so
 * [com.calypsan.listenup.server.services.LibraryRegistry] (which inserts the row inline at
 * bootstrap) can reference the canonical string without duplicating the literal. A rename of the
 * enum member must update this val too — the comment is the guardrail.
 */
internal val SYSTEM_TYPE_ALL_BOOKS: String = SystemCollectionType.ALL_BOOKS.name

/**
 * Persisted column value for the INBOX system collection type.
 *
 * Equals [SystemCollectionType.INBOX].name. Same single-source guarantee as
 * [SYSTEM_TYPE_ALL_BOOKS].
 */
internal val SYSTEM_TYPE_INBOX: String = SystemCollectionType.INBOX.name
