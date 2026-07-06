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
 * Schema is at v49. Migrations live in the `data/local/migrations/` package
 * (e.g. `Migration14To15`, `Migration19To20`) and are registered via `.addMigrations(...)`
 * in each platform `DatabaseModule`. Pre-launch policy: `fallbackToDestructiveMigration(true)`
 * is set on each `DatabaseModule`; before launch, flip the fallback to `false` and
 * verify the full migration chain is registered. The `@Database.exportSchema`
 * on-disk JSON for each version is the authoritative source for writing migrations.
 *
 * v47 → v48 (avatar single-source): dropped the `user_profiles` table and the
 * `users.avatarType`/`avatarValue`/`avatarColor` columns. Avatar state now lives solely in the
 * synced `public_profiles` row. No hand-written migration — the pre-launch destructive fallback
 * recreates the schema (beta DBs re-sync from the server), which is acceptable pre-release.
 *
 * v48 → v49: adds the covering index `index_activities_deletedAt_revision_id` on `activities`
 * (digest/liveIds become index-only scans on the append-forever table). Hand-written migration:
 * `Migration48To49`.
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
        CoverDownloadTaskEntity::class,
        SyncCursorEntity::class,
        PendingOperationV2Entity::class,
        AdminUserRosterEntity::class,
        BookReadershipEntity::class,
        CachedActiveSessionEntity::class,
    ],
    version = 49,
    exportSchema = true,
)
@TypeConverters(
    ValueClassConverters::class,
    Converters::class,
    CoverDownloadStatusConverter::class,
    StringListJsonConverter::class,
    UserEditedFieldsConverter::class,
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

    abstract fun coverDownloadDao(): CoverDownloadDao

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
