package com.calypsan.listenup.client.data.local.db

import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import org.w3c.dom.Worker

/**
 * Browser database location: an OPFS-backed file inside the SQLite worker's VFS.
 *
 * The [Worker] is resolved rather than created — `:app:sharedLogic` ships no worker script,
 * so spawning it is the consuming application's job (`:app:webApp`'s `createSqliteWorker`).
 *
 * Construction lives in [buildConfigured], as on every other platform. The default query
 * context is right here without special-casing: js `IODispatcher` is `Dispatchers.Default`,
 * and the web driver is asynchronous message-passing anyway, so there is no blocking pool to
 * want.
 */
internal actual val platformDatabaseModule: Module =
    module {
        single {
            Room
                .databaseBuilder<ListenUpDatabase>(name = "listenup.db")
                .buildConfigured(WebWorkerSQLiteDriver(get()))
        }
    }
