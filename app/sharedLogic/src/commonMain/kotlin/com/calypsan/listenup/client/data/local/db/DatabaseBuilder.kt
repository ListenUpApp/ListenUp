package com.calypsan.listenup.client.data.local.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import com.calypsan.listenup.core.IODispatcher
import kotlin.coroutines.CoroutineContext

/**
 * The single place a [ListenUpDatabase] is constructed, on every platform.
 *
 * This used to be three near-identical builder chains, one per platform `DatabaseModule`, each
 * repeating the driver, the dispatcher, the FTS callback registration, and a six-line comment
 * about the migration policy. The triplication was not harmless: the FTS callback was once
 * registered on two platforms and missed on the third, and every search on Apple devices failed
 * with `no such table: books_fts` until someone noticed. The fix at the time was a comment
 * explaining that the callback was required — which left the structure that permitted the bug
 * fully intact.
 *
 * Room 3's context-optional `databaseBuilder` is what finally allows the collapse: Android was
 * already passing an absolute path, so its `Context` argument was redundant. Three chances to
 * forget a step become none.
 *
 * **Migration policy (non-destructive).** No `fallbackToDestructiveMigration` call appears here,
 * deliberately, so a schema mismatch with no migration throws loudly rather than silently
 * recreating the database. That matters because the local database holds the unsynced outbox
 * (`PendingOperationV2Entity`) and `syncedAt`-pending playback and listening rows — data the
 * "it re-syncs from the server" story does not cover, because it never reached the server. Every
 * schema-version bump must ship a hand-written migration that preserves those rows.
 * `DatabaseMigrationPolicyTest` fails the build if the destructive fallback is ever re-added —
 * and now has one call site to police instead of three.
 *
 * The seam sits on the [RoomDatabase.Builder] rather than on a path string, because
 * `Room.databaseBuilder` resolves against the per-platform generated constructor and so is not
 * callable from common code. Platforms answer "where" by handing over a builder; this function
 * answers "how" and is the only thing that does.
 *
 * The in-memory test database builds through here too (via [queryContext], which tests set to
 * their own dispatcher). That is deliberate: it means every FTS-backed test exercises the real
 * [FtsTableCallback] registration rather than a test-local copy of it, so the wiring bug this
 * function exists to prevent cannot hide behind a green suite.
 *
 * @param driver the platform's SQLite implementation. Native platforms pass
 *   `BundledSQLiteDriver()`; a browser target would pass the `sqlite-web` driver, because
 *   `sqlite-bundled` links the SQLite C amalgamation and publishes no js variant. Which
 *   implementation exists is a "where" fact, so it belongs to the platform alongside the
 *   builder — this function still owns the "how".
 * @param queryContext the coroutine context Room runs queries on. Defaults to [IODispatcher],
 *   the single canonical background dispatcher — it resolves to the real elastic IO pool on
 *   every platform, including Native.
 */
internal fun RoomDatabase.Builder<ListenUpDatabase>.buildConfigured(
    driver: SQLiteDriver,
    queryContext: CoroutineContext = IODispatcher,
): ListenUpDatabase =
    setDriver(driver)
        .setQueryCoroutineContext(queryContext)
        // The three `*_fts` virtual tables are not Room entities (Room 3.0.0's @Fts5 is broken on
        // Kotlin/Native — see FtsTableCallback), so they are created here. This is the ONLY
        // registration site; missing it on one platform is the bug that motivated this seam.
        .addCallback(FtsTableCallback())
        // Hand-written, non-destructive migrations — registered here so every platform inherits
        // them from the single builder seam (see the migration policy above).
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
