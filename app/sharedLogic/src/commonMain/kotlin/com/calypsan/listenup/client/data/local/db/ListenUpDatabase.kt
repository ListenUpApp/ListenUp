package com.calypsan.listenup.client.data.local.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.ColumnTypeConverters
import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryFolderDao
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity

/**
 * Room database for ListenUp client.
 *
 * Stores user data, books, and sync metadata for offline-first functionality.
 *
 * Schema is at **v20** — the Room 3 baseline (v1) plus the [MIGRATION_1_2] volume-boost columns, the
 * [MIGRATION_2_3] `books.normalizationGainDb` tag-fallback column, the [MIGRATION_3_4] per-user
 * permission flags (`admin_user_roster.canEdit`, `users.canEdit`/`canShare`), the
 * [MIGRATION_4_5] presence-cache columns (`cached_active_sessions.lastActiveAtMs`/`isLive`), the
 * [MIGRATION_7_8] two-tier chapter-grouping columns (`books.bookTierLabel`/`partTierLabel`,
 * `chapters.partTitle`/`bookTitle`), the [MIGRATION_8_9] `book_ratings` table (one listener's
 * rating of one book, mirroring `book_moods`' junction shape), and the [MIGRATION_9_10]
 * `book_external_ratings` table (how one outside catalog rates one book — server-written only,
 * no outbox). [MIGRATION_10_11] changes no schema: it drops the `UNKNOWN`-source rows PR-2 builds
 * stored and rewinds that domain's cursor, so the next catch-up re-pulls them decoded.
 * [MIGRATION_11_12] adds `book_readership.hardcoverFinishesJson`, the cached Hardcover reads of each reader.
 * [MIGRATION_12_13] drops the inert "Can share" columns, `users.canShare` and `admin_user_roster.canShare`.
 * [MIGRATION_13_14] adds `book_external_ratings.fetchedAt` and rewinds that domain's cursor to fill it.
 * [MIGRATION_14_15] moves each reader's also-on-Hardcover finishes out of a `finishesJson` suffix and into
 * `book_readership.finishesAlsoOnHardcoverJson`.
 * [MIGRATION_15_16] adds the series tree, `series.parentId` and `series.parentPosition`.
 * [MIGRATION_16_17] adds `libraries.metadataRegion`, the library's Audible store.
 * [MIGRATION_17_18] adds `books.lastMatch`, the book's live metadata match for "Undo last match".
 * [MIGRATION_18_19] adds `users.canCurateLibrary` and `admin_user_roster.canCurateLibrary`, backfilled from `canEdit`.
 * [MIGRATION_19_20] adds the Story World `entities` mirror, the two Story World flags on `users` and
 * `admin_user_roster`, and `pending_operation.mayHaveLanded`.
 * [MIGRATION_20_21] adds reading orders (#962): the `reading_orders`, `reading_order_books` and
 * `reading_order_follows` tables, `canMakeReadingOrders` on `users` and `admin_user_roster`, and
 * `books.releaseDate` for Publication order.
 * **v1** was the squashed starting point: the pre-1.0 chain (old v1 → v2 → v3) was squashed to a
 * single starting point alongside the Room 2.8.4 → Room 3 migration, while the app was still
 * pre-production and no install base held a database worth preserving. Everything those migrations
 * added is folded into this baseline: the `syncId` columns on `collection_books`/`book_tags`/
 * `book_moods` (SERVER-SYNC-04 — junction wire ids became opaque, so the client stores the
 * server-assigned id instead of deriving `"$a:$b"` at read time) and the contributor FTS
 * `sortName`/`aliases` columns.
 *
 * That squash was a one-off with a closing window, not a repeatable manoeuvre: it is only sound
 * while wiping every local database is acceptable. It is not, from the first real user onward —
 * which is exactly what the migration policy below exists to enforce.
 *
 * **Migration policy (non-destructive).** The platform `DatabaseModule`s do NOT call
 * `fallbackToDestructiveMigration`, so a schema mismatch with no migration throws loudly instead of
 * silently recreating the DB. That matters because the local DB holds the **unsynced outbox**
 * (`PendingOperationV2Entity`) plus `syncedAt`-pending playback/listening rows — data the "re-syncs
 * from the server" story does NOT cover, because it never reached the server. **Every future
 * schema-version bump MUST ship a hand-written [androidx.room3.migration.Migration]** (register it in
 * `buildConfigured` (DatabaseBuilder.kt)) that preserves the outbox and other pending rows; the guard
 * `DatabaseMigrationPolicyTest` fails the build if the destructive fallback is ever re-added. The
 * `@Database.exportSchema` on-disk JSON (`schemas/…/<version>.json`) is the authoritative baseline.
 */
@Database(
    entities = [
        UserEntity::class,
        LibraryEntity::class,
        LibraryFolderEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        SeriesEntity::class,
        ContributorEntity::class,
        BookContributorCrossRef::class,
        ContributorAliasCrossRef::class,
        BookSeriesCrossRef::class,
        PlaybackPositionEntity::class,
        DownloadEntity::class,
        CollectionEntity::class,
        CollectionBookEntity::class,
        CollectionShareEntity::class,
        ShelfEntity::class,
        ShelfBookEntity::class,
        TagEntity::class,
        BookTagEntity::class,
        MoodEntity::class,
        BookMoodEntity::class,
        GenreEntity::class,
        BookGenreCrossRef::class,
        AudioFileEntity::class,
        BookDocumentEntity::class,
        ListeningEventEntity::class,
        ActivityEntity::class,
        UserStatsEntity::class,
        UserPreferencesEntity::class,
        PublicProfileEntity::class,
        TentativeSpanEntity::class,
        SyncCursorEntity::class,
        PendingOperationV2Entity::class,
        AdminUserRosterEntity::class,
        BookReadershipEntity::class,
        CachedActiveSessionEntity::class,
        NotificationEntity::class,
        BookRatingEntity::class,
        BookExternalRatingEntity::class,
        EntityEntity::class,
        ReadingOrderEntity::class,
        ReadingOrderBookEntity::class,
        ReadingOrderFollowEntity::class,
        WorldEventEntity::class,
        WorldEventMentionEntity::class,
    ],
    version = 22,
    exportSchema = true,
)
@ColumnTypeConverters(
    ValueClassConverters::class,
    Converters::class,
    StringListJsonConverter::class,
    FieldProvenanceConverter::class,
    LastMatchConverter::class,
    EntityKindConverter::class,
    WorldEventTypeConverter::class,
)
@ConstructedBy(ListenUpDatabaseConstructor::class)
@Suppress("TooManyFunctions")
internal abstract class ListenUpDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun libraryDao(): LibraryDao

    abstract fun libraryFolderDao(): LibraryFolderDao

    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun seriesDao(): SeriesDao

    abstract fun contributorDao(): ContributorDao

    abstract fun contributorAliasDao(): ContributorAliasDao

    abstract fun bookContributorDao(): BookContributorDao

    abstract fun bookSeriesDao(): BookSeriesDao

    abstract fun playbackPositionDao(): PlaybackPositionDao

    abstract fun downloadDao(): DownloadDao

    abstract fun searchDao(): SearchDao

    abstract fun collectionDao(): CollectionDao

    abstract fun collectionBookDao(): CollectionBookDao

    abstract fun collectionShareDao(): CollectionShareDao

    abstract fun shelfDao(): ShelfDao

    abstract fun shelfBookDao(): ShelfBookDao

    abstract fun tagDao(): TagDao

    abstract fun bookTagDao(): BookTagDao

    abstract fun moodDao(): MoodDao

    abstract fun bookMoodDao(): BookMoodDao

    abstract fun genreDao(): GenreDao

    abstract fun audioFileDao(): AudioFileDao

    abstract fun bookDocumentDao(): BookDocumentDao

    abstract fun listeningEventDao(): ListeningEventDao

    abstract fun activityDao(): ActivityDao

    abstract fun userStatsDao(): UserStatsDao

    abstract fun userPreferencesDao(): UserPreferencesDao

    abstract fun publicProfileDao(): PublicProfileDao

    abstract fun tentativeSpanDao(): TentativeSpanDao

    abstract fun syncCursorDao(): SyncCursorDao

    abstract fun pendingOperationV2Dao(): PendingOperationV2Dao

    abstract fun adminUserRosterDao(): AdminUserRosterDao

    abstract fun bookReadershipDao(): BookReadershipDao

    abstract fun cachedActiveSessionDao(): CachedActiveSessionDao

    abstract fun notificationDao(): NotificationDao

    abstract fun bookRatingDao(): BookRatingDao

    abstract fun bookExternalRatingDao(): BookExternalRatingDao

    abstract fun entityDao(): EntityDao

    abstract fun worldEventDao(): WorldEventDao

    abstract fun readingOrderDao(): ReadingOrderDao

    abstract fun readingOrderBookDao(): ReadingOrderBookDao

    abstract fun readingOrderFollowDao(): ReadingOrderFollowDao
}

/**
 * Room database constructor for KMP.
 * The expect declaration is needed for commonMain compilation.
 * The actual implementations are generated by Room KSP for each platform (Android, iOS).
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect object ListenUpDatabaseConstructor : RoomDatabaseConstructor<ListenUpDatabase> {
    override fun initialize(): ListenUpDatabase
}
