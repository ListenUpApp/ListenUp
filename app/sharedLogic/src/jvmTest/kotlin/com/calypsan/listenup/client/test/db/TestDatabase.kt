package com.calypsan.listenup.client.test.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.buildConfigured
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

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

/**
 * Cancels every coroutine in [scopes], waits for each one to finish, and only then closes this database.
 *
 * The order is the whole point. Cancelling a scope does not stop a statement the driver is already
 * running on another thread, and the bundled SQLite driver frees its connection on [close] without
 * waiting for one: the statement then reads freed memory and the JVM dies with SIGSEGV, killing the
 * whole test run rather than failing a test. `scope.cancel()` followed by `db.close()` is exactly
 * that race, so teardown of a test that runs database work in its own scope goes through here.
 *
 * Runs [NonCancellable] so a teardown in the `finally` of a cancelled or failed test still waits.
 */
internal suspend fun ListenUpDatabase.closeAfterCancelling(vararg scopes: CoroutineScope) {
    withContext(NonCancellable) {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
    }
    close()
}
