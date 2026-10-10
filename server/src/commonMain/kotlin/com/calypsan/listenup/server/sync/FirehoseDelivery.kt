package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.isAdmin

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

/**
 * Book↔tag / book↔mood / book↔rating / book↔outside-rating junction rows. Access-gated: a row is
 * keyed to a book (plus a tag, mood, listener, or outside catalog), so an ungated one tells a
 * member the id of a book they cannot see — and, for a rating, that a stranger rated it and by how
 * much.
 */
internal const val BOOK_TAGS_DOMAIN = "book_tags"
internal const val BOOK_MOODS_DOMAIN = "book_moods"
internal const val BOOK_RATINGS_DOMAIN = "book_ratings"
internal const val BOOK_EXTERNAL_RATINGS_DOMAIN = "book_external_ratings"

/**
 * Story World entities. Access-gated by home: a book-homed entity is visible iff its book is, a
 * series-homed one iff at least one of the series' books is.
 */
internal const val ENTITIES_DOMAIN = "entities"

/**
 * Story World events. Access-gated by home, like entities, and by the anchor book: an anchored event is
 * visible only while its book is.
 */
internal const val WORLD_EVENTS_DOMAIN = "world_events"

/**
 * Reading-order membership rows (#962). Gated like the book junctions above: a row names a book, so an
 * ungated one would tell a member that a book they can't see exists and which order holds it.
 */
internal const val READING_ORDER_BOOKS_DOMAIN = "reading_order_books"

/** The book-keyed junction domains whose live events [isBookJunctionEventHidden] gates on the payload's book. */
private val BOOK_JUNCTION_DOMAINS =
    setOf(
        BOOK_TAGS_DOMAIN,
        BOOK_MOODS_DOMAIN,
        BOOK_RATINGS_DOMAIN,
        BOOK_EXTERNAL_RATINGS_DOMAIN,
        READING_ORDER_BOOKS_DOMAIN,
    )

internal const val LIBRARY_FOLDERS_DOMAIN = "library_folders"

// Admin-only domain: a row carries a user's email/role/status, which non-admins must never
// see. Whole-domain by role, same shape as LIBRARY_FOLDERS_DOMAIN above — members hold no
// roster rows at all, so there is nothing for them to reconcile and tombstones need not pass
// through.
internal const val ADMIN_USER_ROSTER_DOMAIN = "admin_user_roster"

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
        isBookEventHidden(
            busEvent = busEvent,
            userId = userId,
            role = role,
            bookAccessPolicy = bookAccessPolicy,
        ) -> "book"

        isActivityEventHidden(
            busEvent = busEvent,
            userId = userId,
            role = role,
            bookAccessPolicy = bookAccessPolicy,
        ) -> "activity"

        isCollectionEventHidden(
            busEvent = busEvent,
            userId = userId,
            role = role,
            bookAccessPolicy = bookAccessPolicy,
        ) -> "collection"

        isBookJunctionEventHidden(
            busEvent = busEvent,
            userId = userId,
            role = role,
            bookAccessPolicy = bookAccessPolicy,
        ) -> "bookJunction"

        isStoryWorldEventHidden(
            busEvent = busEvent,
            userId = userId,
            role = role,
            bookAccessPolicy = bookAccessPolicy,
        ) -> "storyWorld"

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
    if (role.isAdmin()) return false
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
    if (role.isAdmin()) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    // Gate on the row's book_id (from the payload), not the event id (which is the activity id).
    val bookId = activityBookIdOf(busEvent.event) ?: return false
    return !bookAccessPolicy().canAccess(userId, role, bookId)
}

/**
 * Whether a live `book_tags`/`book_moods`/`book_ratings`/`book_external_ratings`/`reading_order_books` junction event
 * must be withheld from `(userId, role)`.
 *
 * Mirrors [isActivityEventHidden]: ROOT/ADMIN and Deleted tombstones always pass — a tombstone
 * strands no secret, and its payload is minimized to strip the pair anyway. Content events gate on
 * the payload's `bookId`, never the row id (which is opaque and encodes neither side of the pair).
 *
 * **Fails closed.** A gated domain whose content payload [junctionBookIdOf] can't resolve to a
 * `bookId` — i.e. an unrecognised payload type — is withheld, not delivered: the next junction
 * domain added here must not leak silently just because [junctionPayloadBookId] forgot its branch.
 */
private suspend fun isBookJunctionEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    val domain = busEvent.repo.domainName
    if (domain !in BOOK_JUNCTION_DOMAINS) return false
    if (role.isAdmin()) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    val bookId = junctionBookIdOf(busEvent.event) ?: return true
    return !bookAccessPolicy().canAccess(userId, role, bookId)
}

/**
 * The `bookId` on a junction content [event]. The repo↔event type binding guarantees the payload
 * is the matching junction type by construction.
 */
private fun junctionBookIdOf(event: SyncEvent<*>): String? =
    when (event) {
        is SyncEvent.Created<*> -> junctionPayloadBookId(event.payload)
        is SyncEvent.Updated<*> -> junctionPayloadBookId(event.payload)
        is SyncEvent.Deleted -> null
    }

private fun junctionPayloadBookId(payload: Any?): String? =
    when (payload) {
        is BookTagSyncPayload -> payload.bookId
        is BookMoodSyncPayload -> payload.bookId
        is BookRatingSyncPayload -> payload.bookId
        is ExternalRatingSyncPayload -> payload.bookId
        is ReadingOrderBookSyncPayload -> payload.bookId
        else -> null
    }

/**
 * The `bookId` carried by a content [event] on the `activities` domain, or `null` when the row is
 * a public (non-book) activity or a tombstone (already handled upstream). The repo↔event type
 * binding guarantees the payload is an [ActivitySyncPayload] by construction.
 */
private fun activityBookIdOf(event: SyncEvent<*>): String? =
    when (event) {
        is SyncEvent.Created<*> -> (checkNotNull(event.payload) as ActivitySyncPayload).bookId
        is SyncEvent.Updated<*> -> (checkNotNull(event.payload) as ActivitySyncPayload).bookId
        is SyncEvent.Deleted -> null
    }

/**
 * Whether a live Story World event — on `entities` or `world_events` — must be withheld from `(userId, role)`.
 * Mirrors the catch-up fragments (`BookAccessPolicy.accessibleEntityIdsSql` / `accessibleWorldEventIdsSql`):
 * content events gate on the payload's home, and a world event on its anchor book too; ROOT/ADMIN and
 * tombstones always pass (a tombstone carries no content — each repository's `minimizeTombstone`). One
 * function for both domains keeps [firehoseGateReason]'s chain flat.
 */
private suspend fun isStoryWorldEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    val domain = busEvent.repo.domainName
    if (domain != ENTITIES_DOMAIN && domain != WORLD_EVENTS_DOMAIN) return false
    if (role.isAdmin()) return false
    val payload =
        when (val event = busEvent.event) {
            is SyncEvent.Created<*> -> checkNotNull(event.payload)
            is SyncEvent.Updated<*> -> checkNotNull(event.payload)
            is SyncEvent.Deleted -> return false
        }
    val policy = bookAccessPolicy()
    return when (payload) {
        is EntitySyncPayload -> {
            !policy.canSeeEntityHome(
                userId = userId,
                role = role,
                homeSeriesId = payload.homeSeriesId,
                homeBookId = payload.homeBookId,
            )
        }

        is WorldEventSyncPayload -> {
            !policy.canSeeWorldEvent(
                userId = userId,
                role = role,
                homeSeriesId = payload.homeSeriesId,
                homeBookId = payload.homeBookId,
                anchorBookId = payload.bookId,
            )
        }

        else -> {
            error("unexpected Story World payload ${payload::class} on $domain")
        }
    }
}

/**
 * What a subscriber receives in place of a [busEvent] the gate chain withheld, or null for nothing.
 *
 * A world event is the one gated row whose visibility an edit can take away: re-anchoring it to a book the
 * member can't see (or a revert, or a series-merge undo, moving it) turns its `Updated` hidden, and a member who
 * held the old copy would keep it until the next digest. So a withheld world-event `Updated` becomes a
 * content-free `Deleted` at the same revision — identity only, exactly what an ungated tombstone already carries.
 * A member who never held the row no-ops it; one who regains sight later re-applies the live row at that same
 * revision (the client's guard skips only strictly older revisions). Entities can't move home, and a hidden
 * `Created` was never held, so nothing else is replaced.
 */
internal fun withdrawalFor(busEvent: BusEvent<*>): SyncEvent.Deleted? {
    if (busEvent.repo.domainName != WORLD_EVENTS_DOMAIN) return null
    val update = busEvent.event as? SyncEvent.Updated<*> ?: return null
    return SyncEvent.Deleted(
        id = update.id,
        revision = update.revision,
        occurredAt = update.occurredAt,
        clientOpId = null,
    )
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
): Boolean = busEvent.repo.domainName == LIBRARY_FOLDERS_DOMAIN && !role.isAdmin()

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
): Boolean = busEvent.repo.domainName == ADMIN_USER_ROSTER_DOMAIN && !role.isAdmin()

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
    if (role.isAdmin()) return false
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
        is SyncEvent.Created<*> -> checkNotNull(event.payload) as CollectionBookSyncPayload
        is SyncEvent.Updated<*> -> checkNotNull(event.payload) as CollectionBookSyncPayload
        is SyncEvent.Deleted -> null
    }

/**
 * The [CollectionShareSyncPayload] carried by a content [event] on the `collection_shares`
 * domain, or `null` if the event carries no payload (a tombstone — already handled upstream).
 * The repo↔event type binding guarantees the payload is a share payload by construction.
 */
private fun sharePayloadOf(event: SyncEvent<*>): CollectionShareSyncPayload? =
    when (event) {
        is SyncEvent.Created<*> -> checkNotNull(event.payload) as CollectionShareSyncPayload
        is SyncEvent.Updated<*> -> checkNotNull(event.payload) as CollectionShareSyncPayload
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
