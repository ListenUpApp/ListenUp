package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Persistence layer bindings — all DAO providers sourced from [ListenUpDatabase]
 * and the [TransactionRunner] implementation. Every DAO is a singleton obtained
 * from the Room database instance supplied by `platformDatabaseModule`.
 */
internal val persistenceModule: Module =
    module {
        // Provide DAOs from database
        single { get<ListenUpDatabase>().userDao() }
        single { get<ListenUpDatabase>().bookDao() }
        single { get<ListenUpDatabase>().chapterDao() }
        single { get<ListenUpDatabase>().seriesDao() }
        single { get<ListenUpDatabase>().contributorDao() }
        single { get<ListenUpDatabase>().contributorAliasDao() }
        single { get<ListenUpDatabase>().bookContributorDao() }
        single { get<ListenUpDatabase>().bookSeriesDao() }
        single { get<ListenUpDatabase>().playbackPositionDao() }
        single { get<ListenUpDatabase>().downloadDao() }
        single { get<ListenUpDatabase>().coverDownloadDao() }
        single { get<ListenUpDatabase>().searchDao() }
        single { get<ListenUpDatabase>().collectionDao() }
        single { get<ListenUpDatabase>().collectionBookDao() }
        single { get<ListenUpDatabase>().collectionShareDao() }
        single { get<ListenUpDatabase>().shelfDao() }
        single { get<ListenUpDatabase>().shelfBookDao() }
        single { get<ListenUpDatabase>().readingOrderDao() }
        single { get<ListenUpDatabase>().readingOrderBookDao() }
        single { get<ListenUpDatabase>().readingOrderFollowDao() }
        single { get<ListenUpDatabase>().tagDao() }
        single { get<ListenUpDatabase>().moodDao() }
        single { get<ListenUpDatabase>().bookMoodDao() }
        single { get<ListenUpDatabase>().genreDao() }
        single { get<ListenUpDatabase>().audioFileDao() }
        single { get<ListenUpDatabase>().listeningEventDao() }
        single { get<ListenUpDatabase>().activityDao() }
        single { get<ListenUpDatabase>().userStatsDao() }
        single { get<ListenUpDatabase>().userPreferencesDao() }
        single { get<ListenUpDatabase>().tentativeSpanDao() }
        single { get<ListenUpDatabase>().publicProfileDao() }
        single { get<ListenUpDatabase>().bookReadershipDao() }
        single { get<ListenUpDatabase>().cachedActiveSessionDao() }

        // Hygiene additions: three DAOs consumed by repositories but previously
        // fetched inline via `get<ListenUpDatabase>().xDao()` at the call site
        // rather than through a named Koin binding.
        single { get<ListenUpDatabase>().bookTagDao() }
        single { get<ListenUpDatabase>().libraryDao() }
        single { get<ListenUpDatabase>().libraryFolderDao() }
        single { get<ListenUpDatabase>().bookDocumentDao() }
        single { get<ListenUpDatabase>().adminUserRosterDao() }

        single<TransactionRunner> {
            RoomTransactionRunner(get())
        }
    }
