package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.ColumnAdapter

/**
 * Column adapters for SQLDelight queries. Each aggregate adds its adapters here as it migrates
 * from Exposed to SQLDelight — the [ListenUpDatabase] constructor accepts them by name.
 *
 * Adapter conventions:
 * - Value-class id types (e.g. `BookId`, `UserId`) get a dedicated adapter that round-trips
 *   through `String`. Tag uses a plain `TEXT` column with no adapter needed.
 * - `kotlinx.datetime.Instant` is stored as milliseconds-since-epoch (`INTEGER`) — matching
 *   Exposed's `kotlinx-datetime` extension, keeping existing data readable by both layers
 *   during the incremental cutover.
 *
 * Add adapters here as aggregates are converted.
 */
object Adapters {
    // Aggregate-specific adapters land here as each aggregate migrates, e.g.:
    //
    // val instantAsEpochMillis: ColumnAdapter<Instant, Long> =
    //     object : ColumnAdapter<Instant, Long> {
    //         override fun decode(databaseValue: Long) = Instant.fromEpochMilliseconds(databaseValue)
    //         override fun encode(value: Instant) = value.toEpochMilliseconds()
    //     }
    //
    // val bookId: ColumnAdapter<BookId, String> =
    //     object : ColumnAdapter<BookId, String> {
    //         override fun decode(databaseValue: String) = BookId(databaseValue)
    //         override fun encode(value: BookId) = value.value
    //     }
}
