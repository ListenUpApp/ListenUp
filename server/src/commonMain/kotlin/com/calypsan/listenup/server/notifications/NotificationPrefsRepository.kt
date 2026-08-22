package com.calypsan.listenup.server.notifications

import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.notifications.NotificationTypes
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Per-user per-type delivery preferences. A stored row overrides the type's default; absence means
 * the default applies — so a user who never opened Settings costs zero rows.
 */
class NotificationPrefsRepository(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /** The effective preference for ([userId], [type]): the stored override or [default]. */
    suspend fun resolve(
        userId: String,
        type: String,
        default: NotificationPreference,
    ): NotificationPreference =
        suspendTransaction(db) {
            db.notificationPrefsQueries
                .selectForUserAndType(userId, type)
                .executeAsOneOrNull()
                ?.let { NotificationPreference(inApp = it.in_app != 0L, push = it.push != 0L) }
                ?: default
        }

    /** Every registered type with [userId]'s resolved preference, in registry order. */
    suspend fun listResolved(userId: String): List<NotificationPreferenceDto> {
        val overrides =
            suspendTransaction(db) {
                db.notificationPrefsQueries.selectForUser(userId).executeAsList()
            }.associateBy { it.type }
        return NotificationTypes.all.map { (type, descriptor) ->
            val stored = overrides[type]
            NotificationPreferenceDto(
                type = type,
                preference =
                    stored?.let { NotificationPreference(inApp = it.in_app != 0L, push = it.push != 0L) }
                        ?: descriptor.defaultPreference,
                pushEligible = descriptor.pushEligible,
            )
        }
    }

    /** Stores [preference] as [userId]'s override for [type]. Returns false for an unknown type. */
    suspend fun update(
        userId: String,
        type: String,
        preference: NotificationPreference,
    ): Boolean {
        if (type !in NotificationTypes.all) return false
        suspendTransaction(db) {
            db.notificationPrefsQueries.upsert(
                user_id = userId,
                type = type,
                in_app = if (preference.inApp) 1L else 0L,
                push = if (preference.push) 1L else 0L,
                updated_at = clock.now().toEpochMilliseconds(),
            )
        }
        return true
    }
}
