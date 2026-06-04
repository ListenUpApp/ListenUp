package com.calypsan.listenup.server.absimport

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads an extracted Audiobookshelf `absdatabase.sqlite` on a throwaway **read-only** JDBC
 * connection — never the app's Hikari pool. The connection is opened with `?mode=ro` so the file
 * cannot be mutated, and every query is a static `SELECT` built from [AbsSchema] constants (no
 * user-supplied SQL), reading users, audiobook items, and listening progress.
 *
 * Blocking JDBC: callers invoke this from `withContext(Dispatchers.IO)`. All JDBC faults — a
 * malformed file, a non-ABS database, a missing table — surface as [AbsReadException], never a raw
 * `SQLException`, so the analyzer can map them to a typed `AppError`.
 *
 * Usage:
 * ```
 * AbsBackupReader().open(absDbPath).use { handle ->
 *     val users = handle.users()
 *     val items = handle.bookItems()
 *     val progress = handle.progress()
 * }
 * ```
 */
internal class AbsBackupReader {
    /** Thrown when the ABS database cannot be opened or read (malformed, non-ABS, missing tables). */
    class AbsReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Opens the ABS database read-only and returns a [Handle] for typed reads. The caller owns the
     * handle and must `close()` it (use `.use { }`).
     */
    fun open(absDbPath: Path): Handle {
        val url = "jdbc:sqlite:file:${absDbPath.toAbsolutePath()}?mode=ro"
        val connection =
            try {
                DriverManager.getConnection(url)
            } catch (e: SQLException) {
                throw AbsReadException("Failed to open ABS database at $absDbPath", e)
            }
        return Handle(connection)
    }

    /** A live read-only session over an ABS database. Reads are blocking; close when done. */
    class Handle internal constructor(private val connection: Connection) : AutoCloseable {
        /** Non-guest ABS users with their identity fields. */
        fun users(): List<AbsUser> {
            val sql =
                "SELECT ${AbsSchema.USER_ID}, ${AbsSchema.USER_USERNAME}, ${AbsSchema.USER_EMAIL} " +
                    "FROM ${AbsSchema.USERS} " +
                    "WHERE ${AbsSchema.USER_TYPE} IS NULL OR ${AbsSchema.USER_TYPE} != ?"
            return query(sql, { it.setString(1, AbsSchema.USER_TYPE_GUEST) }) { rs ->
                AbsUser(
                    id = rs.getString(AbsSchema.USER_ID),
                    username = rs.getString(AbsSchema.USER_USERNAME).orEmpty(),
                    email = rs.getString(AbsSchema.USER_EMAIL),
                )
            }
        }

        /** Audiobook library items (podcasts excluded), keyed on the book id, with author name. */
        fun bookItems(): List<AbsItem> {
            val sql =
                "SELECT b.${AbsSchema.BOOK_ID} AS bookId, " +
                    "b.${AbsSchema.BOOK_TITLE} AS title, " +
                    "b.${AbsSchema.BOOK_ASIN} AS asin, " +
                    "b.${AbsSchema.BOOK_ISBN} AS isbn, " +
                    "li.${AbsSchema.LIBRARY_ITEM_REL_PATH} AS relPath, " +
                    "(SELECT a.${AbsSchema.AUTHOR_NAME} FROM ${AbsSchema.AUTHORS} a " +
                    "JOIN ${AbsSchema.BOOK_AUTHORS} ba ON ba.${AbsSchema.BOOK_AUTHOR_AUTHOR_ID} = a.${AbsSchema.AUTHOR_ID} " +
                    "WHERE ba.${AbsSchema.BOOK_AUTHOR_BOOK_ID} = b.${AbsSchema.BOOK_ID} LIMIT 1) AS authorName " +
                    "FROM ${AbsSchema.LIBRARY_ITEMS} li " +
                    "JOIN ${AbsSchema.BOOKS} b ON li.${AbsSchema.LIBRARY_ITEM_MEDIA_ID} = b.${AbsSchema.BOOK_ID} " +
                    "WHERE li.${AbsSchema.LIBRARY_ITEM_MEDIA_TYPE} = ?"
            return query(sql, { it.setString(1, AbsSchema.MEDIA_TYPE_BOOK) }) { rs ->
                AbsItem(
                    id = rs.getString("bookId"),
                    title = rs.getString("title").orEmpty(),
                    asin = rs.getString("asin")?.ifBlank { null },
                    isbn = rs.getString("isbn")?.ifBlank { null },
                    authorName = rs.getString("authorName")?.ifBlank { null },
                    relPath = rs.getString("relPath")?.ifBlank { null },
                )
            }
        }

        /** Listening progress for audiobooks only, correlated on the book id ([AbsItem.id]). */
        fun progress(): List<AbsProgress> {
            val sql =
                "SELECT ${AbsSchema.PROGRESS_USER_ID} AS userId, " +
                    "${AbsSchema.PROGRESS_MEDIA_ITEM_ID} AS itemId, " +
                    "${AbsSchema.PROGRESS_CURRENT_TIME} AS currentTime, " +
                    "${AbsSchema.PROGRESS_DURATION} AS duration, " +
                    "${AbsSchema.PROGRESS_IS_FINISHED} AS isFinished, " +
                    "${AbsSchema.PROGRESS_UPDATED_AT} AS updatedAt " +
                    "FROM ${AbsSchema.MEDIA_PROGRESSES} " +
                    "WHERE ${AbsSchema.PROGRESS_MEDIA_ITEM_TYPE} = ?"
            return query(sql, { it.setString(1, AbsSchema.MEDIA_TYPE_BOOK) }) { rs ->
                val currentTime = rs.getDouble("currentTime")
                val duration = rs.getDouble("duration")
                AbsProgress(
                    userId = rs.getString("userId"),
                    itemId = rs.getString("itemId"),
                    currentTimeSeconds = currentTime,
                    isFinished = rs.getBoolean("isFinished"),
                    progress = if (duration > 0.0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0,
                    lastUpdateMs = parseTimestampMs(rs.getString("updatedAt")),
                )
            }
        }

        override fun close() {
            try {
                connection.close()
            } catch (e: SQLException) {
                throw AbsReadException("Failed to close ABS database", e)
            }
        }

        private fun <T> query(
            sql: String,
            bind: (java.sql.PreparedStatement) -> Unit,
            map: (ResultSet) -> T,
        ): List<T> =
            try {
                connection.prepareStatement(sql).use { statement ->
                    bind(statement)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(map(rs))
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw AbsReadException("Failed to read ABS database", e)
            }
    }

    private companion object {
        /**
         * Parses an ABS `updatedAt` value to epoch millis. Sequelize stores `DataTypes.DATE` in
         * SQLite as ISO-8601 text (e.g. `2022-01-17T04:33:12.000Z`), but tolerates a bare numeric
         * epoch (ms or seconds) for robustness across ABS versions. Unparseable → 0L.
         */
        fun parseTimestampMs(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            raw.toLongOrNull()?.let { numeric ->
                // Heuristic: a 10-digit value is seconds, larger is already millis.
                return if (numeric < SECONDS_THRESHOLD) numeric * MILLIS_PER_SECOND else numeric
            }
            return try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // SQLite's space-separated form ("2022-01-17 04:33:12") isn't ISO-T; normalize it.
                try {
                    Instant.parse(raw.replaceFirst(' ', 'T').let { if (it.endsWith("Z")) it else "${it}Z" })
                        .toEpochMilli()
                } catch (_: DateTimeParseException) {
                    0L
                }
            }
        }

        private const val MILLIS_PER_SECOND = 1_000L

        /** Epoch values below this are treated as seconds (≈ year 33658 in ms). */
        private const val SECONDS_THRESHOLD = 1_000_000_000_000L
    }
}
