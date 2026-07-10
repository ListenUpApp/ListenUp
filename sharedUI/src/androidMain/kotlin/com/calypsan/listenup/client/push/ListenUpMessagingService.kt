package com.calypsan.listenup.client.push

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * Receives FCM token refreshes and incoming data messages for push notifications.
 *
 * Compile-safe skeleton only — [onNewToken] and [onMessageReceived] are wired up in Task C4
 * (token registration + decode → enrich → notify rendering pipeline).
 */
class ListenUpMessagingService : FirebaseMessagingService()
