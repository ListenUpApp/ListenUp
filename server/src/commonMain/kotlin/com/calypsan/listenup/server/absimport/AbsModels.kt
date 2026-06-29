package com.calypsan.listenup.server.absimport

/**
 * Server-internal read structs decoded from an Audiobookshelf SQLite backup.
 *
 * These are NOT wire types — they never cross the RPC boundary. They mirror exactly what
 * [AbsBackupReader] can read from the ABS schema (see [AbsSchema]) and nothing more; the matcher
 * and analyzer translate them into the contract DTOs.
 */
internal data class AbsUser(
    val id: String,
    val username: String,
    val email: String?,
)

/**
 * One audiobook library item, joined `libraryItems` ↔ `books` (`mediaType = 'book'`).
 *
 * [id] is the **`books.id`** (the media id), not the library-item id — it is the key
 * [AbsProgress.itemId] correlates against, because ABS `mediaProgresses` references the book id
 * via `mediaItemId` for audiobooks.
 */
internal data class AbsItem(
    val id: String,
    val title: String,
    val asin: String?,
    val isbn: String?,
    val authorName: String?,
    val relPath: String?,
)

/**
 * One per-user listening-progress row for an audiobook.
 *
 * [itemId] is the ABS `mediaItemId` (= `books.id`), so it joins to [AbsItem.id].
 * [progress] is computed (`currentTime / duration`) — ABS stores it as a getter, not a column.
 * [lastUpdateMs] is epoch **millis**, parsed from the ISO-8601 `updatedAt` text column.
 */
internal data class AbsProgress(
    val userId: String,
    val itemId: String,
    val currentTimeSeconds: Double,
    val isFinished: Boolean,
    val progress: Double,
    val lastUpdateMs: Long,
)

/**
 * One Audiobookshelf playback session for an audiobook — a single bounded listen of [timeListeningSeconds]
 * actual seconds, used to backfill ListeningUp listening-event history.
 *
 * [itemId] is the ABS `mediaItemId` (= `books.id` for `mediaItemType = 'book'`), so it joins to
 * [AbsItem.id] and resolves through the same matcher path as [AbsProgress].
 * [startPositionSeconds]/[endPositionSeconds] are the playhead positions at session start/end.
 * [timeListeningSeconds] is ABS's authoritative actually-listened span (NOT the wall-clock duration).
 * [startedAtMs] is epoch **millis**, parsed from the ISO-8601 `createdAt` text column.
 * [playbackSpeed] defaults to `1.0` — ABS does not store a per-session playback rate.
 * [deviceLabel] is the ABS `mediaPlayer` column (nullable).
 */
internal data class AbsSession(
    val id: String,
    val userId: String,
    val itemId: String,
    val startPositionSeconds: Double,
    val endPositionSeconds: Double,
    val timeListeningSeconds: Double,
    val startedAtMs: Long,
    val playbackSpeed: Float,
    val deviceLabel: String?,
)
