package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.playback.PlaybackPreparer
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/**
 * Registers the single [PlaybackPreparer] binding shared across every platform playback module.
 */
internal fun Module.playbackPreparerSingle(scopeQualifier: Qualifier? = null) {
    single {
        PlaybackPreparer(
            serverConfig = get(),
            playbackPreferences = get(),
            bookDao = get(),
            audioFileDao = get(),
            chapterDao = get(),
            imageStorage = get(),
            progressTracker = get(),
            tokenProvider = get(),
            deviceContext = get(),
            downloadService = get(),
            prepareRepository = get(),
            channel = rpcChannel<BookService>(),
            scope = if (scopeQualifier != null) get(qualifier = scopeQualifier) else get(),
            bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
            localPreferences = get(),
        )
    }
}
