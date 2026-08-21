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

    /**
     * An admin decided this device's pending registration (#1068). Delivered to the
     * registration watch tokens registered pre-auth while the pending screen was open —
     * possession of the unguessable [userId] handle is the credential, exactly as it is for
     * `AuthServicePublic.observeRegistrationStatus`. Tap-through opens the app: approved lands
     * on login, denied shows the denial via the existing status stream.
     */
    @Serializable
    @SerialName("registration_decision")
    data class RegistrationDecision(
        /** The pending registration's user id (the watch key). */
        @SerialName("userId")
        val userId: String,
        /** `true` = approved (sign in now); `false` = denied. */
        @SerialName("approved")
        val approved: Boolean,
    ) : PushPayload

    /**
     * Someone has requested access and is waiting on an admin decision (#1068).
     *
     * Delivered to every live ROOT/ADMIN device. This is the link that makes the rest of the
     * registration chain reachable: [RegistrationDecision] wakes the registrant once a decision
     * exists, but until this payload shipped nothing woke the person who has to make it. An admin
     * with a closed app learned of a pending request only by happening to open the Admin screen,
     * so a registration could sit unseen indefinitely.
     *
     * Carries the id and nothing else, per the IDs-only rule above. The recipient is an admin, so
     * their client already holds the pending user in its synced `ADMIN_USER_ROSTER` mirror and
     * resolves the display name locally — the name never crosses the relay.
     */
    @Serializable
    @SerialName("registration_approval")
    data class RegistrationApproval(
        /** The pending registration's user id — the row to look up, and the one to decide on. */
        @SerialName("userId")
        val userId: String,
    ) : PushPayload

    // Reserved future discriminators (documented, NOT implemented):
    // "password_reset_request" / "password_reset_decision" — spec 2026-08-11 §3, land with the
    // password-reset notification step once the watch mechanism (this file's sibling) is in.
}
