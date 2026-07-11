package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Koin module for the library-write slice: [SelfWriteRegistry] (consulted by the file watcher),
 * [WriteJournal] (crash-resumable manifest log under `$LISTENUP_HOME/write-journal/`), and
 * [LibraryWriteBroker] — the sole component permitted to write inside library folders.
 * [homeDir] is the data-home directory that also holds the live database.
 */
fun libraryWriteModule(homeDir: Path): Module =
    module {
        single {
            val clock: Clock = get()
            SelfWriteRegistry { clock.now().toEpochMilliseconds() }
        }
        single { WriteJournal(Path(homeDir, "write-journal")) }
        single { LibraryWriteBroker(registry = get(), journal = get()) }
    }
