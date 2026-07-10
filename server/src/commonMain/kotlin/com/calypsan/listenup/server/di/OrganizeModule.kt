package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.server.api.OrganizeServiceImpl
import com.calypsan.listenup.server.organize.MoveManifestExecutor
import com.calypsan.listenup.server.organize.OrganizeOnEditRelocator
import com.calypsan.listenup.server.organize.OrganizePlanBuilder
import com.calypsan.listenup.server.organize.OrganizeRunState
import com.calypsan.listenup.server.organize.OrganizerSettingsStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the file/folder organizer slice (#850): the pure [OrganizePlanBuilder], the
 * identity-safe [MoveManifestExecutor] (broker moves + DB path updates), the in-memory
 * [OrganizeRunState], the settings store over `server_settings`, the metadata-edit relocator,
 * and the admin-only [OrganizeServiceImpl] RPC surface. Depends on the library-write slice
 * ([libraryWriteModule]) for the broker and on the books slice for [com.calypsan.listenup.server.services.BookRepository].
 */
fun organizeModule(): Module =
    module {
        single { OrganizerSettingsStore(settings = get()) }
        single { OrganizePlanBuilder(sql = get()) }
        single { MoveManifestExecutor(broker = get(), bookRepository = get()) }
        single { OrganizeRunState() }
        single {
            OrganizeOnEditRelocator(
                settingsStore = get(),
                planBuilder = get(),
                executor = get(),
                scope = get(),
            )
        }
        single {
            OrganizeServiceImpl(
                settingsStore = get(),
                planBuilder = get(),
                executor = get(),
                broker = get(),
                libraryRegistry = get(),
                sql = get(),
                runState = get(),
                runScope = get(),
            )
        }
        single<OrganizeService> { get<OrganizeServiceImpl>() }
    }
