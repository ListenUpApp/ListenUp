package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.UserTable
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Resolves the home [TimeZone] for [userId] from the `users.timezone` column.
 *
 * Defaults to [TimeZone.UTC] when the user has no row or when the stored timezone name is malformed,
 * so stats day-math callers never handle these edge cases. The per-user home timezone
 * (device-reported via login + live events; never imports) is the single consistent frame for all
 * server day-boundary math — streaks and the leaderboard. See #532.
 */
internal suspend fun Database.homeTimeZone(userId: String): TimeZone {
    val name =
        suspendTransaction(this) {
            UserTable
                .select(UserTable.timezone)
                .where { UserTable.id eq userId }
                .firstOrNull()
                ?.get(UserTable.timezone)
        } ?: "UTC"
    return runCatching { TimeZone.of(name) }.getOrDefault(TimeZone.UTC)
}
