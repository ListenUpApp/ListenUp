package com.calypsan.listenup.client.data.local.db

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Browser database location.
 *
 * Empty by design (web seam check). A real binding needs a [ListenUpDatabase] built on
 * `sqlite-web`'s `WebWorkerSQLiteDriver`, whose worker script and `@sqlite.org/sqlite-wasm` npm
 * module the consumer must supply, over an OPFS-backed file — which additionally requires cross-
 * origin isolation (COOP/COEP headers on a trustworthy origin).
 *
 * Empty rather than throwing: an empty module compiles and defers the failure to whoever first
 * resolves a database, which on a compile-only target is nobody.
 */
internal actual val platformDatabaseModule: Module = module { }
