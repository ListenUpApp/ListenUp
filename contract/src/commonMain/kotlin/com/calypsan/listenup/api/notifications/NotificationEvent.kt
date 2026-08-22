package com.calypsan.listenup.api.notifications

import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-type delivery preference — the two axes Settings exposes. `push` is meaningless when the
 * type's descriptor has `pushEligible = false`.
 */
@Serializable
data class NotificationPreference(
    @SerialName("inApp") val inApp: Boolean,
    @SerialName("push") val push: Boolean,
)

/**
 * Static behaviour declaration for a notification type. Never crosses the wire — both sides read
 * it from the type declaration, so there is no second place where "does this push" is decided.
 */
data class NotificationDescriptor(
    /** Rows sharing a key inside the window collapse into one. Null = never coalesce (all of Slice 1). */
    val coalesceKey: String? = null,
    /** How long rows sharing [coalesceKey] keep collapsing into one. Zero = no window. */
    val coalesceWindow: Duration = Duration.ZERO,
    /** Whether this type may ever leave the device as a push. */
    val pushEligible: Boolean,
    /** What a user who has never opened Settings gets. */
    val defaultPreference: NotificationPreference,
)

/**
 * A user-addressed notification — the single declaration of what a notification type IS and how it
 * behaves. Adding a type is: declare the case here (descriptor + target + registry entry — the
 * compiler and [NotificationEventContractTest] force all three), emit it where the thing happens,
 * add its copy. Everything else (row minting, push, preferences, sync, badge) derives from this.
 *
 * Clients MUST render unknown discriminators as a generic notification — see
 * [com.calypsan.listenup.api.sync.NotificationSyncPayload], which carries the event opaquely so an
 * old client's sync never breaks on a new type.
 */
@Serializable
sealed interface NotificationEvent {
    /** Static behaviour declaration for this type. */
    val descriptor: NotificationDescriptor

    /** Where a tap on this notification lands. */
    val target: NotificationTarget

    /** The stable wire discriminator — MUST equal the case's `@SerialName` (contract-tested). */
    val wireType: String

    /** You were invited to a campfire (shared listening session). */
    @Serializable
    @SerialName("campfire_invite")
    data class CampfireInvite(
        @SerialName("campfireId") val campfireId: String,
        @SerialName("bookId") val bookId: String,
        @SerialName("inviterUserId") val inviterUserId: String,
    ) : NotificationEvent {
        override val descriptor: NotificationDescriptor get() = NotificationTypes.CAMPFIRE_INVITE
        override val target: NotificationTarget get() = NotificationTarget.Campfire(campfireId)
        override val wireType: String get() = "campfire_invite"
    }

    /** Your registration was decided — addressed to the applicant. */
    @Serializable
    @SerialName("registration_decision")
    data class RegistrationDecision(
        @SerialName("userId") val userId: String,
        @SerialName("approved") val approved: Boolean,
    ) : NotificationEvent {
        override val descriptor: NotificationDescriptor get() = NotificationTypes.REGISTRATION_DECISION
        override val target: NotificationTarget get() = NotificationTarget.None
        override val wireType: String get() = "registration_decision"
    }

    /**
     * A registration awaits approval — addressed to admins. (The wire name `registration_approval`
     * is frozen: it is what [com.calypsan.listenup.api.push.PushPayload.RegistrationApproval]
     * already ships, and the projection must stay 1:1.)
     */
    @Serializable
    @SerialName("registration_approval")
    data class RegistrationApproval(
        @SerialName("userId") val userId: String,
    ) : NotificationEvent {
        override val descriptor: NotificationDescriptor get() = NotificationTypes.REGISTRATION_APPROVAL
        override val target: NotificationTarget get() = NotificationTarget.AdminInbox
        override val wireType: String get() = "registration_approval"
    }
}

/**
 * The registry of every notification type's descriptor, keyed by wire discriminator. Drives the
 * Settings page (PR 2) and server-side preference resolution without needing an event instance.
 * Completeness against the sealed hierarchy is pinned by [NotificationEventContractTest].
 */
object NotificationTypes {
    /** [NotificationEvent.CampfireInvite]. */
    val CAMPFIRE_INVITE =
        NotificationDescriptor(
            pushEligible = true,
            defaultPreference = NotificationPreference(inApp = true, push = true),
        )

    /** [NotificationEvent.RegistrationDecision]. */
    val REGISTRATION_DECISION =
        NotificationDescriptor(
            pushEligible = true,
            defaultPreference = NotificationPreference(inApp = true, push = true),
        )

    /** [NotificationEvent.RegistrationApproval]. */
    val REGISTRATION_APPROVAL =
        NotificationDescriptor(
            pushEligible = true,
            defaultPreference = NotificationPreference(inApp = true, push = true),
        )

    /** Every type, keyed by wire discriminator. */
    val all: Map<String, NotificationDescriptor> =
        mapOf(
            "campfire_invite" to CAMPFIRE_INVITE,
            "registration_decision" to REGISTRATION_DECISION,
            "registration_approval" to REGISTRATION_APPROVAL,
        )
}
