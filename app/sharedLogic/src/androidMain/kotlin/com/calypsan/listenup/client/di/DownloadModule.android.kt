package com.calypsan.listenup.client.di

import android.content.Context
import androidx.work.WorkManager
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.download.AndroidDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadConstraintObserver
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
 * construct `DownloadManager` and `AndroidDownloadEnqueuer` against the now-`internal`
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
                localPreferences = get<LocalPreferences>(),
                downloadRepository = get(),
                transactionRunner = get(),
                errorBus = get(),
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

        // DownloadEnqueuer seam — Android backend for DownloadRepository.resumeIncompleteDownloads.
        single {
            AndroidDownloadEnqueuer(
                workManager = WorkManager.getInstance(get<Context>()),
                localPreferences = get(),
            )
        } bind DownloadEnqueuer::class
    }
