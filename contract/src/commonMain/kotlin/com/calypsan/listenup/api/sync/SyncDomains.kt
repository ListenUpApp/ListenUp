@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api.sync

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * The catalog of every syncable domain's [SyncDomainKey] — the single source of
 * truth for wire names and payload serializers on both sides of the contract.
 */
@HiddenFromObjC
object SyncDomains {
    /** Book aggregates (root + chapters + documents + audio files + child links). */
    val BOOKS = SyncDomainKey("books", BookSyncPayload.serializer())

    /** Contributors (authors, narrators, and other credited people). */
    val CONTRIBUTORS = SyncDomainKey("contributors", ContributorSyncPayload.serializer())

    /** Series a book may belong to, with sequence position. */
    val SERIES = SyncDomainKey("series", SeriesSyncPayload.serializer())

    /** Genre taxonomy entries. */
    val GENRES = SyncDomainKey("genres", GenreSyncPayload.serializer())

    /** Global, cross-user tags. */
    val TAGS = SyncDomainKey("tags", Tag.serializer())

    /** Book–tag junction rows. */
    val BOOK_TAGS = SyncDomainKey("book_tags", BookTagSyncPayload.serializer())

    /** Global, cross-user moods (the affective axis of a book). */
    val MOODS = SyncDomainKey("moods", Mood.serializer())

    /** Book–mood junction rows. */
    val BOOK_MOODS = SyncDomainKey("book_moods", BookMoodSyncPayload.serializer())

    /** Per-user, per-book playback position checkpoints. */
    val PLAYBACK_POSITIONS = SyncDomainKey("playback_positions", PlaybackPositionSyncPayload.serializer())

    /** Recorded listening sessions, for stats and streak computation. */
    val LISTENING_EVENTS = SyncDomainKey("listening_events", ListeningEventSyncPayload.serializer())

    /** Per-user aggregate listening statistics. */
    val USER_STATS = SyncDomainKey("user_stats", UserStatsSyncPayload.serializer())

    /** Libraries (top-level content roots). */
    val LIBRARIES = SyncDomainKey("libraries", LibrarySyncPayload.serializer())

    /** Filesystem folders scanned into a library. */
    val LIBRARY_FOLDERS = SyncDomainKey("library_folders", LibraryFolderSyncPayload.serializer())

    /** Server-materialized admin user-roster read model (admin clients only). */
    val ADMIN_USER_ROSTER =
        SyncDomainKey("admin_user_roster", AdminUserRosterSyncPayload.serializer())

    /** User-owned collections, including the system ALL_BOOKS and inbox collections. */
    val COLLECTIONS = SyncDomainKey("collections", CollectionSyncPayload.serializer())

    /** Collection↔book junction rows (synthetic `collectionId:bookId` ids). */
    val COLLECTION_BOOKS = SyncDomainKey("collection_books", CollectionBookSyncPayload.serializer())

    /**
     * Collection share grants. Wire name is `collection_shares`; server storage is the
     * `collection_grants` table — the skew is historical and the wire name is frozen
     * (client cursors are keyed by it). This constant IS that freeze.
     */
    val COLLECTION_SHARES =
        SyncDomainKey("collection_shares", CollectionShareSyncPayload.serializer())

    /** User-curated shelves. */
    val SHELVES = SyncDomainKey("shelves", ShelfSyncPayload.serializer())

    /** Book–shelf junction rows. */
    val SHELF_BOOKS = SyncDomainKey("shelf_books", ShelfBookSyncPayload.serializer())

    /** Server-materialized public profiles (leaderboard/social read model). */
    val PUBLIC_PROFILES = SyncDomainKey("public_profiles", PublicProfileSyncPayload.serializer())

    /**
     * Social activity feed rows (started/finished/milestone/shelf events). Server-authored,
     * append-only, book-gated (a row with a non-null bookId is visible only to callers who can
     * access that book). Mirrored so the feed is live cross-device and self-heals.
     */
    val ACTIVITIES = SyncDomainKey("activities", ActivitySyncPayload.serializer())

    /** Every key, for completeness tests and registry-driven iteration. */
    val all: List<SyncDomainKey<*>> =
        listOf(
            BOOKS,
            CONTRIBUTORS,
            SERIES,
            GENRES,
            TAGS,
            BOOK_TAGS,
            MOODS,
            BOOK_MOODS,
            PLAYBACK_POSITIONS,
            LISTENING_EVENTS,
            USER_STATS,
            LIBRARIES,
            LIBRARY_FOLDERS,
            ADMIN_USER_ROSTER,
            COLLECTIONS,
            COLLECTION_BOOKS,
            COLLECTION_SHARES,
            SHELVES,
            SHELF_BOOKS,
            PUBLIC_PROFILES,
            ACTIVITIES,
        )
}
