package com.calypsan.listenup.client.presentation.notifications

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationTarget
import com.calypsan.listenup.api.notifications.toNotificationEvent
import com.calypsan.listenup.api.push.PushPayload
import kotlinx.serialization.SerializationException

/**
 * The shade-tap half of the one tap mapping, callable from Swift. iOS cannot decode the push
 * payload itself ([PushPayload] and contractJson are off the Swift Export surface — deliberately:
 * the fold-in-Kotlin rule), so this object does the decode+projection in Kotlin and hands Swift a
 * [NotificationTarget], which IS exported and onEnum-switchable. Swift's single target switch
 * (PushTapRouter) then maps targets to destinations — the same switch the in-app inbox list uses,
 * which is what keeps the two entry points agreeing. Mirrors [com.calypsan.listenup.client.share.ShareLinkCodec]'s
 * object-member seam shape (Swift calls members, never Kotlin extension functions).
 */
object PushTapRouting {
    /**
     * Decodes a push notification's `payload` JSON (the `userInfo["payload"]` string on iOS) to
     * its tap target. Null for diagnostics (test pushes), unknown future types, and malformed
     * input — all of which mean "just open the app".
     */
    fun targetForPayloadJson(raw: String): NotificationTarget? =
        try {
            contractJson
                .decodeFromString(PushPayload.serializer(), raw)
                .toNotificationEvent()
                ?.target
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
