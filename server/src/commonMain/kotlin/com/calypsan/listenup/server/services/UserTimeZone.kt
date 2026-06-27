package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.datetime.TimeZone

/**
 * Resolves the home [TimeZone] for [userId] from the `users.timezone` column.
 *
 * Defaults to [TimeZone.UTC] when the user has no row or when the stored timezone name is malformed,
 * so stats day-math callers never handle these edge cases. The per-user home timezone
 * (device-reported via login + live events; never imports) is the single consistent frame for all
 * server day-boundary math — streaks and the leaderboard. See #532.
 */
internal suspend fun ListenUpDatabase.homeTimeZone(userId: String): TimeZone {
    val name =
        suspendTransaction(this) {
            usersQueries.selectTimezoneById(id = userId).executeAsOneOrNull()
        } ?: "UTC"
    return runCatching { TimeZone.of(name) }.getOrDefault(TimeZone.UTC)
}
