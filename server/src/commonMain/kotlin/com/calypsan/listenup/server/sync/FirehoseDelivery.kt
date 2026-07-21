package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.api.BookAccessPolicy

// The wire domain names the live firehose and the REST catch-up/digest agree on. Shared between
// this file's live-tail gate chain and SyncRoutes' ACCESS_FILTERS catalog so the two surfaces can
// never disagree on which domain a rule names.

// The access-gated domains: their catch-up + digest are scoped through BookAccessPolicy.
// Every other domain passes a null filter (unchanged behaviour).
internal const val BOOKS_DOMAIN = "books"
internal const val COLLECTIONS_DOMAIN = "collections"

// Book-gated but GLOBAL (not per-user): a row with a non-null book_id is visible iff the caller can
// access that book; book_id IS NULL rows (e.g. user_joined) are public. Unlike books (`id IN
// (accessibleBooks)`) the gate is on the row's book_id, so the access subquery selects visible
// ACTIVITY ids, not book ids. ROOT/ADMIN are unconstrained (null filter).
internal const val ACTIVITIES_DOMAIN = "activities"

// Wire domain stays "collection_shares" while the storage table is collection_grants — a USER grant
// maps to a share on the wire. Do NOT rename to "collection_grants" without a coordinated client
// migration (it would orphan client sync cursors). See CollectionGrantRepository.
internal const val COLLECTION_SHARES_DOMAIN = "collection_shares"
internal const val COLLECTION_BOOKS_DOMAIN = "collection_books"

// Admin-only domain: a row carries an absolute server filesystem path (operator disk
// topology), which members must never see. Unlike the per-row book/collection gates, this
// is whole-domain by role — members hold no folder rows at all, so there is nothing for them
// to reconcile and tombstones need not pass through.
internal const val LIBRARY_FOLDERS_DOMAIN = "library_folders"

// Admin-only domain: a row carries a user's email/role/status, which non-admins must never
// see. Whole-domain by role, same shape as LIBRARY_FOLDERS_DOMAIN above — members hold no
// roster rows at all, so there is nothing for them to reconcile and tombstones need not pass
// through.
internal const val ADMIN_USER_ROSTER_DOMAIN = "admin_user_roster"

internal fun isAdmin(role: UserRole): Boolean = role == UserRole.ROOT || role == UserRole.ADMIN

/**
 * The reason a live firehose [busEvent] must be withheld from `(userId, role)`, or `null` when it
 * may be delivered. The one gate chain the RPC firehose ([SyncStreamServiceImpl]) delivers through
 * — a single visibility definition, matched by the REST catch-up/digest access filters in
 * `SyncRoutes`, so the live tail and REST replay can never disagree on what a subscriber sees.
 */
internal suspend fun firehoseGateReason(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): String? =
    when {
        isBookEventHidden(busEvent, userId, role, bookAccessPolicy) -> "book"
        isActivityEventHidden(busEvent, userId, role, bookAccessPolicy) -> "activity"
        isCollectionEventHidden(busEvent, userId, role, bookAccessPolicy) -> "collection"
        isLibraryFolderEventHidden(busEvent, role) -> "libraryFolder"
        isAdminRosterEventHidden(busEvent, role) -> "adminRoster"
        else -> null
    }

/**
 * Whether a live firehose [busEvent] must be withheld from `(userId, role)` by the
 * book-level access boundary.
 *
 * Only the `books` domain is gated, and only its *content* events (Created/Updated)
 * which carry a payload a member must not see for a private book. ROOT/ADMIN see every
 * book, so they skip the [BookAccessPolicy.canAccess] probe entirely — no DB hit.
 *
 * Deleted tombstones are never hidden: `canAccess` requires `deleted_at IS NULL`, so a
 * deleted book is never "accessible" and probing it would drop every tombstone for every
 * viewer — stranding stale Room rows that can never be reconciled. A tombstone carries
 * only an id (no content), so delivering it to a subscriber who never had the book simply
 * no-ops on their side. One DB probe per gated event per member — fine at our scale.
 */
private suspend fun isBookEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    if (busEvent.repo.domainName != BOOKS_DOMAIN) return false
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    return !bookAccessPolicy().canAccess(userId, role, busEvent.event.id)
}

/**
 * Whether a live firehose [busEvent] on the `activities` domain must be withheld from
 * `(userId, role)`. Book-gated: a row with a non-null `book_id` is hidden unless the caller can
 * access that book; a `book_id == null` row (e.g. `user_joined`) is public and always passes.
 *
 * Mirrors [isBookEventHidden]: ROOT/ADMIN and Deleted tombstones always pass (a tombstone strands
 * no secret). Visibility matches the `activities` catch-up fragment exactly (`book_id IS NULL OR
 * book_id IN accessible`), so the live tail and REST replay never disagree.
 */
private suspend fun isActivityEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    if (busEvent.repo.domainName != ACTIVITIES_DOMAIN) return false
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    // Gate on the row's book_id (from the payload), not the event id (which is the activity id).
    val bookId = activityBookIdOf(busEvent.event) ?: return false
    return !bookAccessPolicy().canAccess(userId, role, bookId)
}

/**
 * The `bookId` carried by a content [event] on the `activities` domain, or `null` when the row is
 * a public (non-book) activity or a tombstone (already handled upstream). The repo↔event type
 * binding guarantees the payload is an [ActivitySyncPayload] by construction.
 */
private fun activityBookIdOf(event: SyncEvent<*>): String? =
    when (event) {
        is SyncEvent.Created<*> -> (event.payload as ActivitySyncPayload).bookId
        is SyncEvent.Updated<*> -> (event.payload as ActivitySyncPayload).bookId
        is SyncEvent.Deleted -> null
    }

/**
 * Whether a live firehose [busEvent] on the `library_folders` domain must be withheld from
 * [role]. The domain is admin-only — its rows carry absolute server filesystem paths — so a
 * non-admin sees nothing on it.
 *
 * Unlike [isBookEventHidden] / [isCollectionEventHidden], tombstones are withheld too: this is
 * a whole-domain gate, not a per-row one, so a member holds no folder rows and has nothing to
 * reconcile. Matches the `LIBRARY_FOLDERS_HIDDEN` catch-up fragment exactly, so the live tail
 * and REST replay never disagree.
 */
private fun isLibraryFolderEventHidden(
    busEvent: BusEvent<*>,
    role: UserRole,
): Boolean = busEvent.repo.domainName == LIBRARY_FOLDERS_DOMAIN && !isAdmin(role)

/**
 * Whether a live firehose [busEvent] on the `admin_user_roster` domain must be withheld from
 * [role]. The domain is admin-only — its rows carry a user's email/role/status — so a
 * non-admin sees nothing on it.
 *
 * Whole-domain gate, same as [isLibraryFolderEventHidden]: tombstones are withheld too, since a
 * member holds no roster rows and has nothing to reconcile. Matches the
 * `ADMIN_USER_ROSTER_HIDDEN` catch-up fragment exactly, so the live tail and REST replay never
 * disagree.
 */
private fun isAdminRosterEventHidden(
    busEvent: BusEvent<*>,
    role: UserRole,
): Boolean = busEvent.repo.domainName == ADMIN_USER_ROSTER_DOMAIN && !isAdmin(role)

/**
 * Whether a live firehose [busEvent] on a collection domain
 * (`collections` / `collection_shares` / `collection_books`) must be withheld from
 * `(userId, role)` by the collection-level access boundary.
 *
 * Mirrors [isBookEventHidden]: only content events (Created/Updated) are gated; ROOT/ADMIN
 * and Deleted tombstones always pass (a tombstone strands no secret — it only lets a client
 * reconcile a row it may already hold; gating it would permanently leave stale rows).
 *
 * Visibility matches each domain's catch-up fragment exactly so the live tail and REST
 * replay never disagree:
 *  - `collections` — the event id *is* the collection id; gated by [BookAccessPolicy.canAccessCollection].
 *  - `collection_books` — the event id is an opaque per-row value (SERVER-SYNC-04: it encodes
 *    nothing), so the collection id comes from the [CollectionBookSyncPayload] carried by the
 *    Created/Updated event, never parsed off the id; gated by that collection's access.
 *  - `collection_shares` — the event id is the grant row id, so the collection id and named
 *    user come from the [CollectionShareSyncPayload]; visible iff the grant names the viewer
 *    or the viewer owns the collection (the `visibleCollectionGrantIdsSql` rule).
 */
private suspend fun isCollectionEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    val domain = busEvent.repo.domainName
    if (domain != COLLECTIONS_DOMAIN && domain != COLLECTION_SHARES_DOMAIN && domain != COLLECTION_BOOKS_DOMAIN) {
        return false
    }
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false

    if (domain == COLLECTION_SHARES_DOMAIN) {
        val share = sharePayloadOf(busEvent.event) ?: return false
        if (share.sharedWithUserId == userId) return false
        return !bookAccessPolicy().ownsCollection(userId, share.collectionId)
    }

    val collectionId =
        if (domain == COLLECTION_BOOKS_DOMAIN) {
            collectionBookPayloadOf(busEvent.event)?.collectionId
        } else {
            busEvent.event.id
        }
    // A missing collection_books payload should never happen for Created/Updated (only Deleted
    // carries none, and that already returned above) — hide defensively rather than bypass.
    return collectionId == null || !bookAccessPolicy().canAccessCollection(userId, role, collectionId)
}

/**
 * Extracts the [CollectionBookSyncPayload] carried by a Created/Updated `collection_books`
 * event, or null for a Deleted event (which carries no payload — callers never reach this for
 * Deleted, since [isCollectionEventHidden] returns early on tombstones). Mirrors [sharePayloadOf].
 */
private fun collectionBookPayloadOf(event: SyncEvent<*>): CollectionBookSyncPayload? =
    when (event) {
        is SyncEvent.Created<*> -> event.payload as CollectionBookSyncPayload
        is SyncEvent.Updated<*> -> event.payload as CollectionBookSyncPayload
        is SyncEvent.Deleted -> null
    }

/**
 * The [CollectionShareSyncPayload] carried by a content [event] on the `collection_shares`
 * domain, or `null` if the event carries no payload (a tombstone — already handled upstream).
 * The repo↔event type binding guarantees the payload is a share payload by construction.
 */
private fun sharePayloadOf(event: SyncEvent<*>): CollectionShareSyncPayload? =
    when (event) {
        is SyncEvent.Created<*> -> event.payload as CollectionShareSyncPayload
        is SyncEvent.Updated<*> -> event.payload as CollectionShareSyncPayload
        is SyncEvent.Deleted -> null
    }

/**
 * Pure predicate: is [lastEventId] behind [bus]'s CURRENT replay-buffer floor? Returns the floor
 * revision (the value a `SyncControl.CursorStale` frame should carry) when stale, `null` when the
 * cursor is fresh. "clientCursor < oldestRetained" → stale; a `null` [lastEventId] or a `null`
 * [ChangeBus.oldestRetainedRevision] (empty buffer) is never stale.
 *
 * Two call sites in [SyncStreamServiceImpl] must agree on this exact check: the pre-subscribe fast
 * path and the attach-time re-check. [ChangeBus] is a hot `MutableSharedFlow` (`replay = 256`,
 * `DROP_OLDEST`), so a subscriber sees the replay cache starting from wherever the floor sits at
 * actual subscription attach, not at the pre-subscribe snapshot taken before that subscription even
 * exists. A burst landing in that gap can evict past [lastEventId] with no live signal — the
 * attach-time re-check closes the window.
 */
internal fun staleCursorFloor(
    bus: ChangeBus,
    lastEventId: Long?,
): Long? {
    val oldestRetained = bus.oldestRetainedRevision()
    return if (lastEventId != null && oldestRetained != null && lastEventId < oldestRetained) oldestRetained else null
}
