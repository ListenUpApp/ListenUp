package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Per-user playback preferences (issue #599). One row per user, created on first write.
 *
 * Keyed by `user_id` (FK → [UserTable], cascade-delete in the migration). The wire DTO
 * [com.calypsan.listenup.api.dto.preferences.UserPreferencesDto] surfaces every column
 * except [updatedAt], which is kept for parity with the Go reference and debugging.
 */
internal object UserSettingsTable : Table("user_settings") {
    val userId = varchar("user_id", 36)
    val defaultPlaybackSpeed = float("default_playback_speed").default(1.0f)
    val defaultSkipForwardSec = integer("default_skip_forward_sec").default(30)
    val defaultSkipBackwardSec = integer("default_skip_backward_sec").default(10)
    val defaultSleepTimerMin = integer("default_sleep_timer_min").nullable()
    val shakeToResetSleepTimer = bool("shake_to_reset_sleep_timer").default(false)
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
