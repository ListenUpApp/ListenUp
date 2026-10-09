package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.core.logging.FileLogSink
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.core.IODispatcher
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Persistent app-log wiring: the rotating [FileLogSink] under the platform files dir.
 *
 * Deliberately lazy (no `createdAtStart`): the sink only touches the filesystem once an
 * entry point resolves it and attaches it to
 * [com.calypsan.listenup.client.core.logging.LogSinkRegistry] after `startKoin` — Android's
 * `ListenUp.onCreate`, desktop's `main` and iOS's `initializeKoin`. Web has no file system and
 * never resolves it; it keeps its recent log in browser storage instead.
 */
internal val loggingModule: Module =
    module {
        single {
            FileLogSink(
                directory = Path(get<StoragePaths>().filesDir, FileLogSink.DIRECTORY_NAME),
                dispatcher = IODispatcher,
            )
        }
    }
