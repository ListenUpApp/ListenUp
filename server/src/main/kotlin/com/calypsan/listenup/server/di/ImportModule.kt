package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.absimport.ImportPaths
import com.calypsan.listenup.server.absimport.ImportStore
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

/**
 * Koin module for the Audiobookshelf-import slice.
 *
 * Provides the filesystem-truth staging primitives:
 *  - [ImportPaths] — working directories under `$LISTENUP_HOME/imports/`.
 *  - [ImportStore] — list/status/delete + analysis/mapping JSON read/write.
 *
 * The analyzer, applier, event bus, and [com.calypsan.listenup.api.ImportService] are added to this
 * module in a later phase; the upload route depends only on [ImportPaths].
 */
fun importModule(homeDir: Path): Module =
    module {
        single { ImportPaths(homeDir) }
        single { ImportStore(get()) }
    }
