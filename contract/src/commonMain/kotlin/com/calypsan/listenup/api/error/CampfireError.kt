package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Errors from the Campfire (co-listening) surface. See the co-listening design
 * spec (2026-07-09) §8. None are retryable — every case needs the caller to pick
 * a different action (rejoin, wait for the host, request access), not a blind retry.
 */
@Serializable
sealed interface CampfireError : AppError {
    /**
     * The campfire doesn't exist, has ended, or was reaped by the idle sweeper.
     *
     * Also the deny-shape for a campfire the caller cannot access at all — an invite-only
     * room they weren't invited to, or a room for a book they can't access — the server
     * never distinguishes "gone" from "never yours to see," matching the
     * `SocialServiceImpl` precedent of never revealing existence to an unauthorized caller.
     */
    @Serializable
    @SerialName("CampfireError.CampfireNotFound")
    data class CampfireNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "That campfire is no longer available."
        override val code: String = "CAMPFIRE_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /** The campfire has reached its member cap; no more joins are accepted. */
    @Serializable
    @SerialName("CampfireError.CampfireFull")
    data class CampfireFull(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "This campfire is full."
        override val code: String = "CAMPFIRE_FULL"
        override val isRetryable: Boolean = false
    }

    /** The caller sent a command, chat message, or reaction to a campfire they haven't joined. */
    @Serializable
    @SerialName("CampfireError.NotAMember")
    data class NotAMember(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "You're not part of this campfire."
        override val code: String = "CAMPFIRE_NOT_A_MEMBER"
        override val isRetryable: Boolean = false
    }

    /** The caller sent a playback command while in host-only control mode without holding the remote. */
    @Serializable
    @SerialName("CampfireError.NotController")
    data class NotController(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "Only the host can control playback right now."
        override val code: String = "CAMPFIRE_NOT_CONTROLLER"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller doesn't have collection access to the campfire's book. Only surfaces from
     * `CampfireService.createSession`, where the caller names a book by id from their own
     * library view — unlike [CampfireNotFound], which is the deny-shape for every other
     * room-access failure once a campfire already exists.
     */
    @Serializable
    @SerialName("CampfireError.BookAccessDenied")
    data class BookAccessDenied(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "You don't have access to that book."
        override val code: String = "CAMPFIRE_BOOK_ACCESS_DENIED"
        override val isRetryable: Boolean = false
    }

    /**
     * A playback command was sent while the campfire is still in its lobby phase (the
     * co-listening lobby amendment, 2026-07-11) — nothing has started yet, so there is no
     * shared anchor to control. Chat and reactions are unaffected; only
     * `CampfireService.sendCommand` returns this.
     */
    @Serializable
    @SerialName("CampfireError.NotStarted")
    data class NotStarted(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CampfireError {
        override val message: String = "The campfire hasn't started yet."
        override val code: String = "CAMPFIRE_NOT_STARTED"
        override val isRetryable: Boolean = false
    }
}
