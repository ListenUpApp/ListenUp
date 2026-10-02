package com.calypsan.listenup.api.dto.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Admin → Hardcover (#1542), as the admin sees it: whether an API token is stored and whose it is
 * ([apiToken] — never the token itself), whether Hardcover may fill gaps when a book is matched
 * ([metadataEnabled], default on), and why it can't right now ([metadataUnavailable]: no API token and
 * nobody connected, so no token can read Hardcover's catalogue).
 */
@Serializable
@SerialName("HardcoverSourceStatus")
data class HardcoverSourceStatus(
    @SerialName("apiToken") val apiToken: HardcoverApiTokenStatus = HardcoverApiTokenStatus.NotSet,
    @SerialName("metadataEnabled") val metadataEnabled: Boolean = true,
    @SerialName("metadataUnavailable") val metadataUnavailable: RatingSourceUnavailable? = null,
)

/**
 * The admin's Hardcover API token, without the token. It is write-only: once saved it is only ever
 * described by whose account it belongs to.
 */
@Serializable
sealed interface HardcoverApiTokenStatus {
    /** No token is stored; catalogue reads borrow a connected account. */
    @Serializable
    @SerialName("HardcoverApiTokenStatus.NotSet")
    data object NotSet : HardcoverApiTokenStatus

    /** A token Hardcover accepted, belonging to Hardcover user [username], stored at [setAt] (epoch ms). */
    @Serializable
    @SerialName("HardcoverApiTokenStatus.Saved")
    data class Saved(
        @SerialName("username") val username: String,
        @SerialName("setAt") val setAt: Long,
    ) : HardcoverApiTokenStatus

    /**
     * Hardcover has since rejected [username]'s token (expired or revoked), or this server can no
     * longer read it. Catalogue reads fall back to a connected account until the admin replaces it.
     */
    @Serializable
    @SerialName("HardcoverApiTokenStatus.Rejected")
    data class Rejected(
        @SerialName("username") val username: String,
    ) : HardcoverApiTokenStatus
}
