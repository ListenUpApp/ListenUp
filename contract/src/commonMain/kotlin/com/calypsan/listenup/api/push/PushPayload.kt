package com.calypsan.listenup.api.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire payload of a push notification, forwarded as an opaque JSON blob by the
 * relay and rendered CLIENT-SIDE (localized text, local enrichment, action
 * buttons). Payloads carry type + IDs ONLY — never names or titles; only the
 * user's own server can resolve the IDs (push design spec 2026-07-10 §4). New
 * notification kinds are new subtypes; the relay and server plumbing never
 * change. Clients MUST render unknown discriminators as a generic notification.
 *
 * NOTE: unlike [com.calypsan.listenup.api.sync.SyncControl], this hierarchy is
 * NOT hidden from the ObjC/Swift export — the iOS client consumes it to render
 * notifications.
 */
@Serializable
sealed interface PushPayload {
    /** Proves the pipeline end-to-end; sent by the Settings test button. */
    @Serializable
    @SerialName("test")
    data class TestNotification(
        /** Server send time, epoch milliseconds. */
        @SerialName("sentAtMs")
        val sentAtMs: Long,
    ) : PushPayload

    /**
     * The recipient was invited to a Campfire (co-listening session). The
     * client enriches inviter/book display data from local Room by ID, falls
     * back to fetching from its own server, then to generic text.
     */
    @Serializable
    @SerialName("campfire_invite")
    data class CampfireInvite(
        /** Campfire session id to join. */
        @SerialName("campfireId")
        val campfireId: String,
        /** Book the session is listening to. */
        @SerialName("bookId")
        val bookId: String,
        /** User who sent the invite. */
        @SerialName("inviterUserId")
        val inviterUserId: String,
    ) : PushPayload

    // Reserved future discriminator (documented, NOT implemented):
    // "registration_approval" — admin approval request with Approve/Deny background actions.
}
