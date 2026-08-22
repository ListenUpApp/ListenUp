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
 * Schema is at **v7** — the Room 3 baseline (v1) plus the [MIGRATION_1_2] volume-boost columns, the
 * [MIGRATION_2_3] `books.normalizationGainDb` tag-fallback column, the [MIGRATION_3_4] per-user
 * permission flags (`admin_user_roster.canEdit`, `users.canEdit`/`canShare`), and the
 * [MIGRATION_4_5] presence-cache columns (`cached_active_sessions.lastActiveAtMs`/`isLive`).
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
 * schema-version bump MUST ship a hand-written [androidx.room3.migration.Migration]** (register it on
 * all three builders) that preserves the outbox and other pending rows; the guard
 * `DatabaseMigrationPolicyTest` fails the build if the destructive fallback is ever re-added. The
 * `@Database.exportSchema` on-disk JSON (`schemas/…/5.json`) is the authoritative baseline.
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
    ],
    version = 7,
    exportSchema = true,
)
@ColumnTypeConverters(
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

    abstract fun notificationDao(): NotificationDao
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
