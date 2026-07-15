package com.calypsan.listenup.client.data.local.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryFolderDao
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity

/**
 * Room database for ListenUp client.
 *
 * Stores user data, books, and sync metadata for offline-first functionality.
 *
 * Schema is at **v8** — a pre-1.0 baseline that squashed the accumulated migration chain
 * (no beta DBs existed to migrate, so the collapse is purely mechanical). There is no
 * migration chain: the pre-launch policy `fallbackToDestructiveMigration(true)` on each
 * platform `DatabaseModule` nukes and recreates the local DB on any schema change (data
 * re-syncs from the server), which is acceptable pre-release — so a version bump needs no
 * hand-written [androidx.room.migration.Migration]. Before launch, flip the fallback to `false`
 * and begin a real migration chain in `data/local/migrations/`; the `@Database.exportSchema`
 * on-disk JSON (`schemas/…/8.json`) is the authoritative baseline.
 *
 * v1 → v2 (nested chapters): adds nullable `partTitle` / `bookTitle` columns to `chapters` —
 * optional Book/Part header labels on the chapter that opens each section.
 *
 * v2 → v3 (reading orders): adds the `reading_orders`, `reading_order_books`, and
 * `reading_order_follows` mirrors (Story World Stage 1 backbone).
 *
 * v3 → v4 (high-water listening frontier): adds `maxPositionMs` to `playback_positions` —
 * the furthest position ever heard in a book, the spoiler-safe frontier for Story World
 * Stage 3.
 *
 * v4 → v5 (trunk merge): adds `tentative_span.processId` for orphan-span process identity —
 * the trunk line's own v1 → v2 bump, renumbered when the feature and trunk lines merged.
 *
 * v5 → v6 (chapter tier vocabulary): adds `bookTierLabel` / `partTierLabel` to `books` — the
 * book's own renamable names for its two chapter-grouping tiers. No hand-written migration —
 * the pre-launch destructive fallback recreates the schema.
 *
 * v6 → v7 (Story World Stage 2 — entities): adds the `entities` and `entity_bio_entries`
 * mirrors — library-shared, curated character/location/item world data namespaced under a
 * series, with a whole-aggregate bio-entry child collection. No hand-written migration — the
 * pre-launch destructive fallback recreates the schema.
 *
 * v7 → v8 (Story World Stage 2 — dual-home entities): `entities` becomes dual-homed under
 * either a series or a standalone book (adds nullable `homeBookId`, `homeSeriesId` becomes
 * nullable) and drops the `entity_bio_entries` child table — bio entries are removed from the
 * contract entirely for this stage. No hand-written migration — the pre-launch destructive
 * fallback recreates the schema.
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
        ReadingOrderEntity::class,
        ReadingOrderBookEntity::class,
        ReadingOrderFollowEntity::class,
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
        CoverDownloadTaskEntity::class,
        SyncCursorEntity::class,
        PendingOperationV2Entity::class,
        AdminUserRosterEntity::class,
        BookReadershipEntity::class,
        CachedActiveSessionEntity::class,
        EntityEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(
    ValueClassConverters::class,
    Converters::class,
    CoverDownloadStatusConverter::class,
    StringListJsonConverter::class,
    UserEditedFieldsConverter::class,
    EntityEnumConverters::class,
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

    abstract fun readingOrderDao(): ReadingOrderDao

    abstract fun readingOrderBookDao(): ReadingOrderBookDao

    abstract fun readingOrderFollowDao(): ReadingOrderFollowDao

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

    abstract fun coverDownloadDao(): CoverDownloadDao

    abstract fun syncCursorDao(): SyncCursorDao

    abstract fun pendingOperationV2Dao(): PendingOperationV2Dao

    abstract fun adminUserRosterDao(): AdminUserRosterDao

    abstract fun bookReadershipDao(): BookReadershipDao

    abstract fun cachedActiveSessionDao(): CachedActiveSessionDao

    abstract fun entityDao(): EntityDao
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
