package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.notifications.NotificationPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One notification type's resolved delivery preference, as the Settings surface consumes it. */
@Serializable
data class NotificationPreferenceDto(
    /** The type's wire discriminator (a `NotificationTypes.all` key). */
    @SerialName("type") val type: String,
    @SerialName("preference") val preference: NotificationPreference,
    /** Mirror of the descriptor, so the Settings page can disable the push toggle without a second lookup. */
    @SerialName("pushEligible") val pushEligible: Boolean,
)
