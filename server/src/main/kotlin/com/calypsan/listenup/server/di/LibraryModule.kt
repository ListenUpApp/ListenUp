package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRepository
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
        single {
            LibraryRepository(
                db = get(),
                bus = get(),
                registry = get(),
            )
        }
        single {
            LibraryFolderRepository(
                db = get(),
                bus = get(),
                registry = get(),
            )
        }
        single<LibraryAdminService> {
            LibraryAdminServiceImpl(
                libraryRepository = get(),
                libraryFolderRepository = get(),
                bookRepository = get(),
                scanOrchestrator = get(),
            )
        }
    }
