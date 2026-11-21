package com.calypsan.listenup.client.di

import android.content.Context
import androidx.work.WorkManager
import com.calypsan.listenup.client.data.repository.BookTransferStore
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.download.AndroidDownloadDeviceState
import com.calypsan.listenup.client.download.BookTransferRegistry
import com.calypsan.listenup.client.download.BookTransferRun
import com.calypsan.listenup.client.download.BookTransferScheduler
import com.calypsan.listenup.client.download.BookTransfers
import com.calypsan.listenup.client.download.DownloadNotifications
import com.calypsan.listenup.client.download.bookTransferSchedulerFor
import com.calypsan.listenup.client.download.AndroidDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadConstraintObserver
import com.calypsan.listenup.client.download.DownloadDeviceState
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadManager
import com.calypsan.listenup.client.download.DownloadService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime `CoroutineScope`. */
private const val APP_SCOPE = "appScope"

/**
 * Android download wiring. Lives in `:app:sharedLogic` (not `:app:sharedUI`) so the bindings can
 * construct `DownloadManager`, the book runs and `AndroidDownloadEnqueuer` against the now-`internal`
 * Room DAOs (`DownloadDao`, `BookDao`, `AudioFileDao`) and `TransactionRunner` —
 * same-module visibility. Mirrors [androidPlaybackModule]: dependencies (including
 * `Context`, registered by the app's `androidContext(...)` Koin start) come through `get()`,
 * so this module needs no `koin-android` dependency. Registered by `ListenUp.kt`; UI-only
 * download bindings stay in `:app:sharedUI`.
 */
val androidDownloadModule: Module =
    module {
        // Download file manager — handles local file operations.
        single { DownloadFileManager(get<Context>()) }

        // Book runs: the shared core, its registry and store, and the transport this device uses.
        single { BookTransferRegistry() }
        single { BookTransferStore(downloadDao = get(), stopEventDao = get(), transactionRunner = get()) }
        single {
            AndroidDownloadDeviceState(context = get<Context>(), localPreferences = get<LocalPreferences>())
        } bind DownloadDeviceState::class
        single {
            BookTransferRun(
                store = get(),
                repository = get(),
                fileManager = get(),
                downloader = get(),
                registry = get(),
                errorBus = get(),
                deviceState = get(),
            )
        }
        single<BookTransferScheduler> { bookTransferSchedulerFor(get<Context>()) }
        single {
            BookTransfers(
                run = get(),
                store = get(),
                registry = get(),
                scheduler = get(),
                // Bound by the app module, which owns the channel, the strings and the actions.
                notifications = get<DownloadNotifications>(),
                bookDao = get(),
                fileManager = get(),
                localPreferences = get<LocalPreferences>(),
                networkMonitor = get<NetworkMonitor>(),
                deviceState = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        // Download manager — coordinates download queue and state.
        // Bound to DownloadService for shared code (PlaybackManager) and exposed as the
        // concrete type for Android-specific features (BookDetailPlatformActions).
        single {
            DownloadManager(
                downloadDao = get(),
                bookDao = get(),
                audioFileDao = get(),
                workManager = WorkManager.getInstance(get<Context>()),
                fileManager = get(),
                downloadRepository = get(),
                transactionRunner = get(),
                errorBus = get(),
                transfers = get(),
            )
        } bind DownloadService::class

        // Wi-Fi-only preference watcher. createdAtStart so it subscribes before the user can
        // reach Settings — a lazily-created observer would miss the very first toggle.
        single(createdAtStart = true) {
            DownloadConstraintObserver(
                localPreferences = get<LocalPreferences>(),
                downloadManager = get<DownloadManager>(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        // DownloadEnqueuer seam — Android backend for DownloadRepository.resumeIncompleteDownloads. Lazy: the
        // repository it serves is itself a dependency of the book runs it schedules.
        single<DownloadEnqueuer> { AndroidDownloadEnqueuer(transfers = lazy { get<BookTransfers>() }) }
    }
