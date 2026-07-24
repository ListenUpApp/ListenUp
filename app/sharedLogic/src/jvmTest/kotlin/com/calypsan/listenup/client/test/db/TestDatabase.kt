package com.calypsan.listenup.client.test.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.FtsTableCallback
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Builds a fresh in-memory [ListenUpDatabase] for a single test. Uses [BundledSQLiteDriver]
 * to match production, so anything the schema/constraints/cascades enforce in the app also
 * holds in tests.
 *
 * Installs the production [FtsTableCallback] so the three `*_fts` virtual tables exist — they
 * are not Room entities and would otherwise be absent, breaking any FTS-backed test. Using the
 * real callback (not a test copy) means every FTS-backed test also guards that callback.
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
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .addCallback(FtsTableCallback())
        .build()
