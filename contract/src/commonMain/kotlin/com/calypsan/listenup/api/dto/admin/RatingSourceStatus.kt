package com.calypsan.listenup.api.dto.admin

import com.calypsan.listenup.api.sync.ExternalRatingSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One outside rating source as the admin sees it. [lastError] is the most recent failure's
 * user-facing summary, null when the last fetch worked.
 */
@Serializable
@SerialName("RatingSourceStatus")
data class RatingSourceStatus(
    @SerialName("source") val source: ExternalRatingSource,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("lastFetchedAt") val lastFetchedAt: Long?,
    @SerialName("lastError") val lastError: String?,
)
