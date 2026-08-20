package com.calypsan.listenup.client.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Receives an Approve/Deny tap from a registration notification and hands it to
 * [RegistrationDecisionWorker].
 *
 * A receiver does the least it possibly can: enqueue, dismiss, return. It has about ten seconds of
 * runtime and no network guarantee, so doing the RPC here would mean an admin's decision quietly
 * evaporating on a slow connection — after the notification had already vanished, which reads as
 * "done" to the person who tapped it.
 *
 * Not exported (see the manifest): these intents come from our own [PendingIntent] and nowhere
 * else. An exported receiver would let any app on the device approve server access by broadcasting
 * an intent, which is the whole permission model gone.
 */
class PushActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DECIDE_REGISTRATION = "com.calypsan.listenup.action.DECIDE_REGISTRATION"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_APPROVE = "approve"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_DECIDE_REGISTRATION) return
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        if (userId == null) {
            logger.warn { "Registration decision action with no user id — ignoring" }
            return
        }
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<RegistrationDecisionWorker>()
                .setInputData(
                    workDataOf(
                        RegistrationDecisionWorker.KEY_USER_ID to userId,
                        RegistrationDecisionWorker.KEY_APPROVE to approve,
                    ),
                ).build(),
        )

        // Dismiss immediately. The work is durable now, so leaving the notification up would invite
        // a second tap on a decision already in flight.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}
