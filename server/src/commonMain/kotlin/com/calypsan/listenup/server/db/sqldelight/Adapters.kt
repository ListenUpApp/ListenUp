package com.calypsan.listenup.server.db.sqldelight

/**
 * Column adapters for SQLDelight queries — the [ListenUpDatabase] constructor accepts them by
 * name. None is registered: every column maps to its SQLite primitive and the repositories
 * convert at the boundary.
 *
 * Adapter conventions, for any that is added:
 * - Value-class id types (e.g. `BookId`, `UserId`) get a dedicated adapter that round-trips
 *   through `String`. Tag uses a plain `TEXT` column with no adapter needed.
 * - `kotlinx.datetime.Instant` is stored as milliseconds-since-epoch (`INTEGER`).
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
