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
 * Schema is at **v2** — the post-squash v1 baseline (the pre-1.0 migration chain was squashed to a
 * single starting point) plus the first post-squash migration: `collection_books`/`book_tags`/
 * `book_moods` each gain a `syncId` column (SERVER-SYNC-04 — junction wire ids became opaque, so the
 * client now stores the server-assigned id instead of deriving `"$a:$b"` at read time). See
 * [MIGRATION_1_2].
 *
 * **Migration policy (non-destructive).** The platform `DatabaseModule`s do NOT call
 * `fallbackToDestructiveMigration`, so a schema mismatch with no migration throws loudly instead of
 * silently recreating the DB. That matters because the local DB holds the **unsynced outbox**
 * (`PendingOperationV2Entity`) plus `syncedAt`-pending playback/listening rows — data the "re-syncs
 * from the server" story does NOT cover, because it never reached the server. **Every future
 * schema-version bump MUST ship a hand-written [androidx.room.migration.Migration]** (register it on
 * all three builders) that preserves the outbox and other pending rows; the guard
 * `DatabaseMigrationPolicyTest` fails the build if the destructive fallback is ever re-added. The
 * `@Database.exportSchema` on-disk JSON (`schemas/…/2.json`) is the authoritative baseline.
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
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(
    ValueClassConverters::class,
    Converters::class,
    StringListJsonConverter::class,
    FieldProvenanceConverter::class,
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
