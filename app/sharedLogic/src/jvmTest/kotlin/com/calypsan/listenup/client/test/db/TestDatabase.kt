package com.calypsan.listenup.client.test.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.buildConfigured
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Builds a fresh in-memory [ListenUpDatabase] for a single test.
 *
 * Builds through the production [buildConfigured] seam, so tests get the real driver and the
 * real `FtsTableCallback` registration — the three `*_fts` virtual tables are not Room entities
 * and would otherwise be absent, breaking any FTS-backed test. Sharing the production seam
 * (rather than re-listing its steps here) means an FTS-backed test cannot pass against wiring
 * that production doesn't actually have.
 *
 * Each call returns an isolated database — tests share no state.
 *
 * Pass [queryContext] = `StandardTestDispatcher(testScheduler)` inside a `runTest` block to
 * make all Room queries run on the test scheduler. This lets `advanceUntilIdle()` drain DB
 * work deterministically, eliminating races between assertions and in-flight IO continuations.
 *
 * Scope: jvmTest only. Promote to commonTest once cross-platform migration tests
 * need the same seam.
 *
 * Source: Room KMP testing guide — https://developer.android.com/kotlin/multiplatform/room.
 */
internal fun createInMemoryTestDatabase(queryContext: CoroutineContext = Dispatchers.IO): ListenUpDatabase =
    Room
        .inMemoryDatabaseBuilder<ListenUpDatabase>()
        .buildConfigured(BundledSQLiteDriver(), queryContext)
