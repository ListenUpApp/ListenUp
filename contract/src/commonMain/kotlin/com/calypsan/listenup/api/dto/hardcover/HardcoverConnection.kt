package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user sees while connecting: go to [verificationUri] and enter [userCode] — or scan a QR
 * of [verificationUriComplete], which pre-fills the code. [expiresAt] (epoch ms) is when the code
 * stops working.
 */
@Serializable
data class HardcoverLinkPrompt(
    @SerialName("userCode") val userCode: String,
    @SerialName("verificationUri") val verificationUri: String,
    @SerialName("verificationUriComplete") val verificationUriComplete: String,
    @SerialName("expiresAt") val expiresAt: Long,
)

/** Why the last attempt to connect ended without a connection. */
@Serializable
enum class HardcoverLinkFailure {
    /** The user declined on Hardcover. */
    DENIED,

    /** The code expired before the user approved it. */
    EXPIRED,

    /** Hardcover could not be reached or answered unexpectedly. */
    UNREACHABLE,
}

/** Why a once-working connection can no longer be used — every one of them needs a reconnect. */
@Serializable
enum class HardcoverBrokenReason {
    /** Hardcover no longer honours the token (revoked there, or its refresh chain was spent). */
    REVOKED,

    /** The stored token can't be decrypted — typically restored onto a server with a different secret. */
    CANNOT_DECRYPT,

    /** The connection lacks a permission ListenUp needs. */
    MISSING_SCOPE,
}

/** The state of the calling user's Hardcover connection, as [com.calypsan.listenup.api.HardcoverService.observeConnection] streams it. */
@Serializable
sealed interface HardcoverConnection {
    /**
     * This server has no Hardcover app configured and the caller has no connection, so there is
     * nothing to show: clients hide the Hardcover entry entirely rather than offer a dead end.
     */
    @Serializable
    @SerialName("HardcoverConnection.NotOffered")
    data object NotOffered : HardcoverConnection

    /** Not connected. [lastLinkFailure] says why the most recent attempt ended, if one just did. */
    @Serializable
    @SerialName("HardcoverConnection.NotConnected")
    data class NotConnected(
        @SerialName("lastLinkFailure") val lastLinkFailure: HardcoverLinkFailure? = null,
    ) : HardcoverConnection

    /** A sign-in is in progress; the server is waiting for the user to approve [prompt] on Hardcover. */
    @Serializable
    @SerialName("HardcoverConnection.Linking")
    data class Linking(
        @SerialName("prompt") val prompt: HardcoverLinkPrompt,
    ) : HardcoverConnection

    /** Connected as [hardcoverUsername] since [since] (epoch ms). */
    @Serializable
    @SerialName("HardcoverConnection.Connected")
    data class Connected(
        @SerialName("hardcoverUsername") val hardcoverUsername: String,
        @SerialName("since") val since: Long,
    ) : HardcoverConnection

    /** Was connected, and now needs a reconnect for [reason]. */
    @Serializable
    @SerialName("HardcoverConnection.Broken")
    data class Broken(
        @SerialName("reason") val reason: HardcoverBrokenReason,
    ) : HardcoverConnection
}
