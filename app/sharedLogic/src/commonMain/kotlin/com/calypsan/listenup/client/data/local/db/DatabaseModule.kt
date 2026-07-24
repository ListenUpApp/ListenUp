package com.calypsan.listenup.client.data.local.db

import org.koin.core.module.Module

/**
 * Supplies the platform's database file path and nothing else.
 *
 * Everything about *how* the database is built — driver, dispatcher, migration policy — lives in
 * [buildConfigured]. Platforms differ only in where a file belongs on disk, so that is the
 * only decision they get to make.
 */
internal expect val platformDatabaseModule: Module
