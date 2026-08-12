package com.calypsan.listenup.client.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.calypsan.listenup.client.data.push.PushAvailability

/**
 * Android [PushAvailability]: whether the user has granted notification delivery at the OS
 * level. On API 33+ (this app's minSdk) [android.Manifest.permission.POST_NOTIFICATIONS] is
 * ungranted by default — an FCM token exists regardless of that grant, so this is the only
 * signal that actually reflects whether a push would be seen.
 */
class AndroidPushAvailability(
    private val context: Context,
) : PushAvailability {
    override suspend fun canDeliver(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
