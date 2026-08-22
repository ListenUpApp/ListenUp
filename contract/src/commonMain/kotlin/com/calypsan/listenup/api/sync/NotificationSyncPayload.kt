package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One row of a user's notification inbox — the `notifications` sync-domain wire shape.
 *
 * The event rides as [type] + opaque [body] JSON rather than a polymorphic field, so an old client
 * receiving a NEW server-side type stores and re-syncs it untouched (rendering it generically)
 * instead of failing the frame decode. [decodeEvent] recovers the typed event where the client
 * understands it.
 */
@Serializable
@SerialName("NotificationSyncPayload")
data class NotificationSyncPayload(
    /** Opaque server-minted row id. */
    @SerialName("id") override val id: String,
    /** The event's wire discriminator — equals `decodeEvent()?.wireType` when known. */
    @SerialName("type") val type: String,
    /** The full contractJson-encoded [NotificationEvent] (including its own `type` field). */
    @SerialName("body") val body: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch ms the user read it, null while unread. Cross-device read state IS this field. */
    @SerialName("readAt") val readAt: Long?,
    @SerialName("revision") override val revision: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Decodes [NotificationSyncPayload.body] to its typed event, or null for a type this client build
 * does not know — callers MUST treat null as "render generically", never as an error.
 */
fun NotificationSyncPayload.decodeEvent(): NotificationEvent? =
    try {
        contractJson.decodeFromString(NotificationEvent.serializer(), body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
