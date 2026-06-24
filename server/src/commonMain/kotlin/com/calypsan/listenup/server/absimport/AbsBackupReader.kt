@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.server.db.SqlAdminConnection
import com.calypsan.listenup.server.db.SqlBinder
import com.calypsan.listenup.server.db.SqlRow
import com.calypsan.listenup.server.db.openAdminConnection
import kotlin.time.Instant

/**
 * Reads an extracted Audiobookshelf `absdatabase.sqlite` on a throwaway **read-only**
 * [SqlAdminConnection] — never the app's own database. The connection is opened read-only so the
 * file cannot be mutated, and every query is a static `SELECT` built from [AbsSchema] constants (no
 * user-supplied SQL), reading users, audiobook items, and listening progress.
 *
 * Callers invoke this from `withContext(Dispatchers.IO)`. All faults — a malformed file, a non-ABS
 * database, a missing table — surface as [AbsReadException], never a raw exception, so the analyzer
 * can map them to a typed `AppError`.
 *
 * Usage:
 * ```
 * AbsBackupReader().open(absDbPath).use { handle ->
 *     val users = handle.users()
 *     val items = handle.bookItems()
 *     val progress = handle.progress()
 *     val sessions = handle.playbackSessions()
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
    fun open(absDbPath: String): Handle {
        val conn =
            try {
                openAdminConnection(absDbPath, readOnly = true)
            } catch (e: Throwable) {
                throw AbsReadException("Failed to open ABS database at $absDbPath", e)
            }
        return Handle(conn)
    }

    /** A live read-only session over an ABS database. Reads are blocking; close when done. */
    class Handle internal constructor(private val conn: SqlAdminConnection) : AutoCloseable {
        /** Non-guest ABS users with their identity fields. */
        fun users(): List<AbsUser> {
            val sql =
                "SELECT ${AbsSchema.USER_ID}, ${AbsSchema.USER_USERNAME}, ${AbsSchema.USER_EMAIL} " +
                    "FROM ${AbsSchema.USERS} " +
                    "WHERE ${AbsSchema.USER_TYPE} IS NULL OR ${AbsSchema.USER_TYPE} != ?"
            return read(sql, { bindString(1, AbsSchema.USER_TYPE_GUEST) }) { row ->
                AbsUser(
                    id = row.getString(AbsSchema.USER_ID)!!,
                    username = row.getString(AbsSchema.USER_USERNAME).orEmpty(),
                    email = row.getString(AbsSchema.USER_EMAIL),
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
            return read(sql, { bindString(1, AbsSchema.MEDIA_TYPE_BOOK) }) { row ->
                AbsItem(
                    id = row.getString("bookId")!!,
                    title = row.getString("title").orEmpty(),
                    asin = row.getString("asin")?.ifBlank { null },
                    isbn = row.getString("isbn")?.ifBlank { null },
                    authorName = row.getString("authorName")?.ifBlank { null },
                    relPath = row.getString("relPath")?.ifBlank { null },
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
            return read(sql, { bindString(1, AbsSchema.MEDIA_TYPE_BOOK) }) { row ->
                val currentTime = row.getDouble("currentTime")
                val duration = row.getDouble("duration")
                AbsProgress(
                    userId = row.getString("userId")!!,
                    itemId = row.getString("itemId")!!,
                    currentTimeSeconds = currentTime,
                    isFinished = row.getBoolean("isFinished"),
                    progress = if (duration > 0.0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0,
                    lastUpdateMs = parseAbsTimestampMs(row.getString("updatedAt")),
                )
            }
        }

        /**
         * Playback sessions for audiobooks only (podcasts excluded), correlated on the book id
         * ([AbsItem.id]). Maps the ABS `mediaPlayer` column to [AbsSession.deviceLabel]; the start
         * timestamp is the ISO-8601 `createdAt` column (ABS's session start). ABS stores no
         * per-session playback rate, so [AbsSession.playbackSpeed] defaults to `1.0`.
         */
        fun playbackSessions(): List<AbsSession> {
            val sql =
                "SELECT ${AbsSchema.SESSION_ID} AS id, " +
                    "${AbsSchema.SESSION_USER_ID} AS userId, " +
                    "${AbsSchema.SESSION_MEDIA_ITEM_ID} AS itemId, " +
                    "${AbsSchema.SESSION_START_TIME} AS startTime, " +
                    "${AbsSchema.SESSION_CURRENT_TIME} AS currentTime, " +
                    "${AbsSchema.SESSION_TIME_LISTENING} AS timeListening, " +
                    "${AbsSchema.SESSION_STARTED_AT} AS startedAt, " +
                    "${AbsSchema.SESSION_DEVICE} AS deviceLabel " +
                    "FROM ${AbsSchema.PLAYBACK_SESSIONS} " +
                    "WHERE ${AbsSchema.SESSION_MEDIA_ITEM_TYPE} = ?"
            return read(sql, { bindString(1, AbsSchema.MEDIA_TYPE_BOOK) }) { row ->
                AbsSession(
                    id = row.getString("id")!!,
                    userId = row.getString("userId")!!,
                    itemId = row.getString("itemId")!!,
                    startPositionSeconds = row.getDouble("startTime"),
                    endPositionSeconds = row.getDouble("currentTime"),
                    timeListeningSeconds = row.getDouble("timeListening"),
                    startedAtMs = parseAbsTimestampMs(row.getString("startedAt")),
                    playbackSpeed = DEFAULT_PLAYBACK_SPEED,
                    deviceLabel = row.getString("deviceLabel")?.ifBlank { null },
                )
            }
        }

        override fun close() {
            try {
                conn.close()
            } catch (e: Throwable) {
                throw AbsReadException("Failed to close ABS database", e)
            }
        }

        private fun <T> read(
            sql: String,
            bind: SqlBinder.() -> Unit = {},
            map: (SqlRow) -> T,
        ): List<T> =
            try {
                conn.query(sql, bind, map)
            } catch (e: Throwable) {
                throw AbsReadException("Failed to read ABS database", e)
            }
    }

    private companion object {
        /** ABS stores no per-session playback rate; sessions default to normal speed. */
        private const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}

private const val MILLIS_PER_SECOND = 1_000L

/** Epoch values below this are treated as seconds (≈ year 33658 in ms). */
private const val SECONDS_THRESHOLD = 1_000_000_000_000L

/**
 * Parses an ABS timestamp to epoch millis.
 *
 * ABS (Sequelize on SQLite) stores `DataTypes.DATE` columns as space-separated text with a
 * millisecond fraction and an explicit offset, e.g. `2024-06-12 02:48:10.063 +00:00`. The cleaner
 * ISO-8601 form (`2022-01-17T04:33:12.000Z`), the offsetless SQLite form (`2022-01-16 04:33:12`),
 * and a bare numeric epoch (ms or seconds) are also accepted. Anything unparseable → 0L.
 *
 * `internal` so it can be unit-tested directly against the real-world formats — the offset form
 * silently parsing to 0L is exactly what mis-ordered Continue Listening after an ABS import.
 */
internal fun parseAbsTimestampMs(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    val trimmed = raw.trim()
    trimmed.toLongOrNull()?.let { numeric ->
        // Heuristic: a 10-digit value is seconds, larger is already millis.
        return if (numeric < SECONDS_THRESHOLD) numeric * MILLIS_PER_SECOND else numeric
    }
    // Normalize the SQLite text form to ISO-8601: swap the date/time space for 'T' and drop the
    // space before the offset ("2024-06-12 02:48:10.063 +00:00" → "2024-06-12T02:48:10.063+00:00").
    val isoLike = trimmed.replaceFirst(' ', 'T').replace(" ", "")
    return try {
        // Instant.parse handles an explicit offset (e.g. "+00:00") and the trailing 'Z' (UTC) form.
        Instant.parse(isoLike).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        // Offsetless form ("2022-01-16T04:33:12") — treat as UTC.
        try {
            Instant.parse(if (isoLike.endsWith("Z")) isoLike else "${isoLike}Z").toEpochMilliseconds()
        } catch (_: IllegalArgumentException) {
            0L
        }
    }
}
