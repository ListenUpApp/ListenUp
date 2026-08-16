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
 * `ListenUp.onCreate` and desktop's `main`. Platforms without a logging tap (web, iOS for
 * now) simply never resolve it.
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
