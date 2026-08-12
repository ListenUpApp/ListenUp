package com.calypsan.listenup.client.data.push

/**
 * Platform hook reporting whether this device can actually receive a push notification right
 * now — i.e. whether the user has granted delivery at the OS level, not merely whether a
 * platform token exists. [PushTokenProvider.currentToken] alone is not a reliable signal: on
 * Android 13+ (this app's minSdk) FCM happily returns a token even when
 * `POST_NOTIFICATIONS` was never granted, so a device with a token can still be one the OS
 * silently drops every push for.
 *
 * Bound only where the platform can meaningfully answer that question — the Android platform
 * module checks `POST_NOTIFICATIONS` via `NotificationManagerCompat`. Other platforms bind
 * [AlwaysAvailablePush]: iOS's [PushTokenProvider] (`ApnsTokenStore`) already gates on the OS
 * permission prompt — APNs registration only ever yields a token after the user grants it — so a
 * second availability check there would be redundant.
 */
interface PushAvailability {
    /** Whether a push sent to this device right now would actually be delivered/shown. */
    suspend fun canDeliver(): Boolean
}

/**
 * No-gate [PushAvailability] for platforms where [PushTokenProvider.currentToken] already implies
 * deliverability (see [PushAvailability]'s KDoc).
 */
data object AlwaysAvailablePush : PushAvailability {
    override suspend fun canDeliver(): Boolean = true
}
