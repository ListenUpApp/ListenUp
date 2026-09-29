package com.calypsan.listenup.api.dto.admin

import com.calypsan.listenup.api.sync.ExternalRatingSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One outside rating source as the admin sees it. [lastError] is the most recent failure's
 * user-facing summary, null when the last fetch worked. [pausedUntil] (epoch millis) is set while
 * the source has paused itself after repeated failures; [unavailable] says why it cannot run at
 * all; [connectionUsername] names the account a connection-backed source (Hardcover) is using.
 */
@Serializable
@SerialName("RatingSourceStatus")
data class RatingSourceStatus(
    @SerialName("source") val source: ExternalRatingSource,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("lastFetchedAt") val lastFetchedAt: Long?,
    @SerialName("lastError") val lastError: String?,
    @SerialName("pausedUntil") val pausedUntil: Long? = null,
    @SerialName("unavailable") val unavailable: RatingSourceUnavailable? = null,
    @SerialName("connectionUsername") val connectionUsername: String? = null,
)
