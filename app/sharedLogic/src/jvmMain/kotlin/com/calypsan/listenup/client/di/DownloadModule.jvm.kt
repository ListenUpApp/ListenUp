package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.JvmDownloadEnqueuer
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop download wiring. Lives in `:app:sharedLogic` so `DownloadEnqueuer` (whose signature
 * names the now-`internal` `DownloadEntity`) need not be public for a `:app:sharedUI` binding —
 * mirrors `androidDownloadModule`. Registered by the desktop app's Koin start (`Main.kt`).
 */
val desktopDownloadModule: Module =
    module {
        single<DownloadEnqueuer> { JvmDownloadEnqueuer() }
    }
