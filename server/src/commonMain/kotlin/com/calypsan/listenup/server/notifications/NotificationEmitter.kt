package com.calypsan.listenup.server.notifications

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.toPushPayload
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.NotificationSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.sync.NotificationRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException

/** Who a notification is addressed to. Explicit, never inferred — call sites say it. */
sealed interface NotificationAudience {
    /** One user. */
    data class User(
        val userId: String,
    ) : NotificationAudience

    /** A fixed set of users. */
    data class Users(
        val userIds: List<String>,
    ) : NotificationAudience

    /** Every current ROOT and ADMIN. */
    data object Admins : NotificationAudience
}

/**
 * The ONLY seam call sites touch to notify a user of anything. Resolves the audience, applies each
 * user's per-type preference, mints inbox rows (which the sync substrate fans out to devices),
 * prunes retention, and hands push-eligible events to the [PushNotifier].
 *
 * Never throws (short of cancellation): a notification failure must not fail the business write
 * that triggered it — same contract as [PushNotifier]. Each audience member is isolated: one
 * user's failure never aborts the fan-out to the rest.
 */
class NotificationEmitter(
    private val db: ListenUpDatabase,
    private val repo: NotificationRepository,
    private val prefs: NotificationPrefsRepository,
    private val notifier: PushNotifier,
) {
    private val log = loggerFor<NotificationEmitter>()

    /** Delivers [event] to every member of [to], per each member's preferences. */
    suspend fun emit(
        event: NotificationEvent,
        to: NotificationAudience,
    ) {
        val audience =
            try {
                resolve(to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only — payloads carry user-addressed content (PushNotifier's logging contract).
                log.warn { "notification audience resolve failed: type=${event.wireType} ${e::class.simpleName}" }
                return
            }
        audience.forEach { userId ->
            try {
                emitToUser(event, userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "notification emit failed: type=${event.wireType} ${e::class.simpleName}" }
            }
        }
    }

    private suspend fun emitToUser(
        event: NotificationEvent,
        userId: String,
    ) {
        val pref = prefs.resolve(userId, event.wireType, event.descriptor.defaultPreference)
        if (pref.inApp) {
            val minted =
                repo.upsert(
                    NotificationSyncPayload(
                        id = Uuid.random().toString(),
                        type = event.wireType,
                        body = contractJson.encodeToString(NotificationEvent.serializer(), event),
                        createdAt = 0L, // server-assigned in writePayload
                        updatedAt = 0L,
                        readAt = null,
                        revision = 0L,
                        deletedAt = null,
                    ),
                    userId = userId,
                )
            when (minted) {
                is AppResult.Success -> {
                    repo.pruneToRetention(userId, RETENTION_PER_USER)
                }

                is AppResult.Failure -> {
                    log.warn { "notification row mint failed: type=${event.wireType} code=${minted.error.code}" }
                }
            }
        }
        if (event.descriptor.pushEligible && pref.push) {
            event.toPushPayload()?.let { notifier.notify(userId, it) }
        }
    }

    private suspend fun resolve(to: NotificationAudience): List<String> =
        when (to) {
            is NotificationAudience.User -> {
                listOf(to.userId)
            }

            is NotificationAudience.Users -> {
                to.userIds
            }

            is NotificationAudience.Admins -> {
                suspendTransaction(db) { db.usersQueries.selectAdminIds().executeAsList() }
            }
        }

    companion object {
        /** Rows kept per user; older ones are pruned on write. 200 is a guess (spec open decision). */
        const val RETENTION_PER_USER = 200
    }
}
