package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.dto.BookMoodMutation
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.dto.BookTagMutation
import com.calypsan.listenup.api.dto.CollectionBookMutation
import com.calypsan.listenup.api.dto.CollectionMutation
import com.calypsan.listenup.api.dto.ContributorMutation
import com.calypsan.listenup.api.dto.GenreMutation
import com.calypsan.listenup.api.dto.NotificationMutation
import com.calypsan.listenup.api.dto.ShelfBookMutation
import com.calypsan.listenup.api.dto.ReadingOrderBookMutation
import com.calypsan.listenup.api.dto.ReadingOrderFollowMutation
import com.calypsan.listenup.api.dto.ReadingOrderMutation
import com.calypsan.listenup.api.dto.ShelfMutation
import com.calypsan.listenup.api.dto.TagMutation
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SeriesMutation
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.sync.SyncDomains

/**
 * Every declared client→server write channel — the complete outbox rulebook.
 *
 * Mirrored domains take their name from their [SyncDomains] key so the contract,
 * the descriptor's [WriteTier.Outbox], and the queue all share one identity.
 * `profile` and `preferences` are client-only channels: RPC edit surfaces with no
 * mirrored descriptor (profile's inbound echo arrives via the `public_profiles`
 * mirror; preferences' via the `PreferencesChanged` refreshed domain).
 */
internal object OutboxChannels {
    // The unified book-edit payload: the PATCH plus every replace-set (contributors, series, genres,
    // chapters, collections, cover removal), each last-write-wins → inherently idempotent. One channel
    // so a book's edits share per-entity FIFO and the domain-keyed anti-flicker shield.
    val Books =
        OutboxChannel(
            name = SyncDomains.BOOKS.name,
            serializer = BookMutation.serializer(),
            ops = setOf(OpKind.Update),
            idempotent = true,
        )

    // Series lifecycle: update (Update) is last-write-wins; delete (Delete) cascades server-side. Both
    // are idempotent — a re-fire re-applies the same terminal state. Merging two series stays online.
    val Series =
        OutboxChannel(
            name = SyncDomains.SERIES.name,
            serializer = SeriesMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Contributor lifecycle: update (Update) is last-write-wins; delete (Delete) cascades server-side.
    // Both are idempotent. Merging/un-merging a contributor stays online (server relinks junctions).
    val Contributors =
        OutboxChannel(
            name = SyncDomains.CONTRIBUTORS.name,
            serializer = ContributorMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // PlaybackService.recordPosition is documented "Idempotent and lastPlayedAt-wins server-side."
    val Positions =
        OutboxChannel(
            name = SyncDomains.PLAYBACK_POSITIONS.name,
            serializer = RecordPositionRequest.serializer(),
            ops = setOf(OpKind.Upsert),
            idempotent = true,
        )

    // PlaybackService.recordListeningEvent is documented "Idempotent (re-recording the same id ...)."
    val ListeningEvents =
        OutboxChannel(
            name = SyncDomains.LISTENING_EVENTS.name,
            serializer = RecordListeningEventRequest.serializer(),
            ops = setOf(OpKind.Upsert),
            idempotent = true,
        )
    val Profile =
        OutboxChannel(
            name = "profile",
            serializer = UpdateProfileRequest.serializer(),
            ops = setOf(OpKind.Update),
            idempotent = true,
        )
    val Preferences =
        OutboxChannel(
            name = "preferences",
            serializer = UpdateUserPreferencesRequest.serializer(),
            ops = setOf(OpKind.Update),
            idempotent = true,
        )

    // Genre lifecycle: update (Update) is last-write-wins; delete (Delete) cascades server-side. Both are
    // idempotent — a re-fire re-applies the same terminal state (a second delete finds the genre already
    // tombstoned). Creating a genre (server-minted id/slug), a subtree move (path/depth recompute), and a
    // merge (server-side relink) all stay online.
    val Genres =
        OutboxChannel(
            name = SyncDomains.GENRES.name,
            serializer = GenreMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Tag lifecycle: rename (Update) is last-write-wins; delete (Delete) cascades server-side. Both are
    // idempotent — a re-fire re-applies the same terminal state (a second delete finds the tag already
    // tombstoned; the optimistic delete + echo have already converged, so at worst a spurious dead-letter).
    val Tags =
        OutboxChannel(
            name = SyncDomains.TAGS.name,
            serializer = TagMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Junction add/remove: both idempotent server-side (re-adding an existing junction or re-removing an
    // absent one returns Success). Add (Create) is offline-first only for the name-hit case — a same-name
    // tag/mood already exists locally, so the server's find-or-create resolves to that same id; a
    // genuinely-new tag/mood mints a server id and stays online (never enqueued as an Add).
    val BookTags =
        OutboxChannel(
            name = SyncDomains.BOOK_TAGS.name,
            serializer = BookTagMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Delete),
            idempotent = true,
        )
    val BookMoods =
        OutboxChannel(
            name = SyncDomains.BOOK_MOODS.name,
            serializer = BookMoodMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Delete),
            idempotent = true,
        )

    // A listener's rating: Set and Clear both carry the whole terminal state for (book, listener),
    // under one kind, so the queue coalesces them — three taps offline send one op. Idempotent
    // server-side (re-rating overwrites; clearing an absent rating succeeds).
    val BookRatings =
        OutboxChannel(
            name = SyncDomains.BOOK_RATINGS.name,
            serializer = BookRatingMutation.serializer(),
            ops = setOf(OpKind.Upsert),
            idempotent = true,
        )

    // Shelf lifecycle: update (Update) is last-write-wins; delete (Delete) cascades server-side. Both are
    // idempotent — a re-fire re-applies the same terminal state. Creating a shelf stays online (server-minted id).
    val Shelves =
        OutboxChannel(
            name = SyncDomains.SHELVES.name,
            serializer = ShelfMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Junction add/remove/reorder: all idempotent server-side (re-adding an existing member or
    // re-removing an absent one returns Success; a permutation re-writes the same indices). Unlike
    // book_tags/book_moods, adding a book mints no server id — the book already exists — so add is
    // offline-first too. Reorder rides the same channel under Update, keyed by SHELF rather than by
    // junction, which is what lets it coalesce — see ShelfRepositoryImpl.reorderBooks.
    val ShelfBooks =
        OutboxChannel(
            name = SyncDomains.SHELF_BOOKS.name,
            serializer = ShelfBookMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Delete, OpKind.Update),
            idempotent = true,
        )

    // Reading orders (#962) are offline-first from the start, create included: the client mints the id, so
    // a create replays idempotently (same id, same maker → Success). Rename is last-write-wins; delete is
    // idempotent (NotFound drains as success).
    val ReadingOrders =
        OutboxChannel(
            name = SyncDomains.READING_ORDERS.name,
            serializer = ReadingOrderMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Reading-order membership: add (Create) and remove (Delete) are idempotent server-side and keyed by
    // junction; reorder rides Update keyed by the ORDER id, so it coalesces, and the server merges it
    // tolerantly, so it may land either side of an add or a remove.
    val ReadingOrderBooks =
        OutboxChannel(
            name = SyncDomains.READING_ORDER_BOOKS.name,
            serializer = ReadingOrderBookMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Delete, OpKind.Update),
            idempotent = true,
        )

    // A user's choice of order on one series: Choose and Clear each carry the whole terminal state for
    // (user, series), under one kind, so the queue coalesces them — the BookRatings precedent.
    val ReadingOrderFollows =
        OutboxChannel(
            name = SyncDomains.READING_ORDER_FOLLOWS.name,
            serializer = ReadingOrderFollowMutation.serializer(),
            ops = setOf(OpKind.Upsert),
            idempotent = true,
        )

    // Collection lifecycle: rename (Update) is last-write-wins; delete (Delete) cascades server-side. Both are
    // idempotent. Creating a collection stays online (server-minted id).
    val Collections =
        OutboxChannel(
            name = SyncDomains.COLLECTIONS.name,
            serializer = CollectionMutation.serializer(),
            ops = setOf(OpKind.Update, OpKind.Delete),
            idempotent = true,
        )

    // Junction add/remove: both idempotent server-side. Adding a book mints no server id, so add is
    // offline-first too. The `collection_books` domain is access-gated; the outbox flip touches only its writes.
    val CollectionBooks =
        OutboxChannel(
            name = SyncDomains.COLLECTION_BOOKS.name,
            serializer = CollectionBookMutation.serializer(),
            ops = setOf(OpKind.Create, OpKind.Delete),
            idempotent = true,
        )

    /** markRead ops for the notifications inbox — per-row last-write-wins, safely re-fired. */
    val Notifications =
        OutboxChannel(
            name = SyncDomains.NOTIFICATIONS.name,
            serializer = NotificationMutation.serializer(),
            ops = setOf(OpKind.Update),
            idempotent = true,
        )

    // Story World entities: create and edit are one Upsert (a full snapshot with a client-minted id, applied
    // in arrival order like every synced write) and delete is Delete. Both idempotent — a re-fired upsert
    // re-applies the same snapshot; a re-fired delete finds the entity already gone (NotFound folds to
    // success). Merge and revert stay online.
    val Entities =
        OutboxChannel(
            name = SyncDomains.ENTITIES.name,
            serializer = EntityMutation.serializer(),
            ops = setOf(OpKind.Upsert, OpKind.Delete),
            idempotent = true,
        )

    /** The complete, ordered channel list — the set the sender map must bind exactly. */
    val all: List<OutboxChannel<*>> =
        listOf(
            Books,
            Series,
            Contributors,
            Positions,
            ListeningEvents,
            Profile,
            Preferences,
            Genres,
            Tags,
            BookTags,
            BookMoods,
            BookRatings,
            Shelves,
            ShelfBooks,
            ReadingOrders,
            ReadingOrderBooks,
            ReadingOrderFollows,
            Collections,
            CollectionBooks,
            Notifications,
            Entities,
        )

    private val byName: Map<String, OutboxChannel<*>> = all.associateBy { it.name }

    /**
     * Whether re-firing an op on [domainName] after a provably-sent-but-unconfirmed drop
     * (TransportError.OutcomeUnknown) is safe. Unknown domain → false: quarantine conservatively
     * rather than risk double-applying a mutation whose idempotency was never declared.
     */
    fun isIdempotent(domainName: String): Boolean = byName[domainName]?.idempotent == true
}
