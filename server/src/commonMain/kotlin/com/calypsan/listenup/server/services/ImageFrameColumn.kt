package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.image.ImageFraming
import kotlinx.serialization.SerializationException

/** An [ImageFraming] as the `image_frame` / `avatar_frame` columns store it: the contract's JSON. */
internal fun ImageFraming.toImageFrameColumn(): String = contractJson.encodeToString(ImageFraming.serializer(), this)

/** A frame column read back. Null for an empty column, and for one this build cannot read: that photo then shows centred. */
internal fun String?.toImageFramingOrNull(): ImageFraming? {
    if (isNullOrBlank()) return null
    return try {
        contractJson.decodeFromString(ImageFraming.serializer(), this)
    } catch (_: SerializationException) {
        null
    }
}
