package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRepository
import app.cash.sqldelight.db.SqlDriver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the library-admin slice. Wires:
 *
 *  - [LibraryRepository] — syncable repository for the `libraries` table.
 *  - [LibraryFolderRepository] — syncable repository for the `library_folders` table.
 *  - [LibraryAdminServiceImpl] bound as [LibraryAdminService] — implements the full
 *    lifecycle API (create / rename / delete / add-folder / remove-folder / scan).
 *
 * Installed unconditionally at application startup (libraries are not gated behind
 * a scanner path). Depends on [BookRepository], [ScanOrchestrator], and the DB
 * from other modules.
 */
fun libraryModule(): Module =
    module {
        // createdAtStart = true is mandatory for every SyncableRepository: the init block
        // self-registers the domain with SyncRegistry at bootstrap, so /api/v1/sync/domains
        // and the firehose's library_folders gating are correct on the very first request —
        // rather than relying on an incidental eager deref during routing setup.
        single(createdAtStart = true) {
            LibraryRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            LibraryFolderRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                driver = get<SqlDriver>(),
            )
        }
        single<LibraryAdminService> {
            LibraryAdminServiceImpl(
                libraryRepository = get(),
                libraryFolderRepository = get(),
                bookRepository = get(),
                scanOrchestrator = get(),
                libraryRegistry = get(),
                clock = get(),
            )
        }
    }
