package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.JvmDownloadEnqueuer
import com.calypsan.listenup.client.download.NoDownloadsService
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop download wiring. Lives in `:app:sharedLogic` so two `internal` types can be
 * constructed without being made public for a `:app:sharedUI` binding — mirrors
 * `androidDownloadModule`. Registered by the desktop app's Koin start (`Main.kt`).
 *
 * - `DownloadEnqueuer` — [JvmDownloadEnqueuer]'s signature names the `internal` `DownloadEntity`.
 * - `DownloadService` — [NoDownloadsService] is `internal` so this stub-in-name-only
 *   implementation never lands on the Swift-Export flat-typealias surface (a public commonMain
 *   type is exported to every client, iOS included, whether or not that client can reach it).
 *   The browser's identical binding costs nothing extra: `:app:webApp`'s Koin graph is assembled
 *   from `:app:sharedLogic` modules directly, so `PlaybackModule.js.kt` binds it in place.
 */
val desktopDownloadModule: Module =
    module {
        single<DownloadEnqueuer> { JvmDownloadEnqueuer() }
        single<DownloadService> { NoDownloadsService() }
    }
