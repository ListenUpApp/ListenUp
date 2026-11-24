package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Sessions
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

internal suspend fun markLastLogin(
    db: ListenUpDatabase,
    clock: Clock,
    userId: String,
    timezone: String,
) {
    val now = clock.now().toEpochMilliseconds()
    suspendTransaction(db) {
        db.usersQueries.updateLastLoginAndTimezone(last_login_at = now, timezone = timezone, id = userId)
    }
}

internal fun Sessions.toSessionSummary(currentSessionId: SessionId): SessionSummary =
    SessionSummary(
        id = SessionId(id),
        label = label,
        deviceInfo = deviceInfoOf(this),
        userAgent = user_agent,
        createdAt = created_at,
        lastUsedAt = last_used_at,
        current = id == currentSessionId.value,
    )

internal fun deviceInfoOf(s: Sessions): DeviceInfo? {
    // The read path must not assume stored rows honour the current DeviceInfo
    // invariants: legacy sessions (pre-validation) may hold blank
    // or over-long fields. Sanitize before constructing so DeviceInfo.init's
    // length `require` — the correct *inbound* guard — never throws on outbound data.
    fun field(value: String?): String? = value?.takeIf { it.isNotBlank() }?.take(DEVICE_FIELD_MAX)
    val info =
        DeviceInfo(
            deviceType = field(s.device_type),
            platform = field(s.platform),
            platformVersion = field(s.platform_version),
            clientName = field(s.client_name),
            clientVersion = field(s.client_version),
            deviceName = field(s.device_name),
            deviceModel = field(s.device_model),
        )
    return info.takeIf { it != DeviceInfo() }
}
