package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Room mirror of the user's notification inbox (Notifications — Room v7).
 *
 * Rows arrive from the server's notifications domain via the sync engine; the payload is stored
 * verbatim as [eventJson] so unknown event types survive a round-trip on an older client instead
 * of being dropped. Soft-deletes are tombstoned via [deletedAt]; the read state is an optimistic
 * local stamp on [readAt] (see [NotificationDao.markRead]).
 *
 * @property id Server-assigned notification id — the sync identity.
 * @property type Wire discriminator — kept as a column so queries never parse [eventJson].
 * @property eventJson The contractJson-encoded NotificationEvent, stored verbatim (unknown types
 *   included).
 * @property createdAt Epoch-ms the notification was minted, server clock — the inbox sort key.
 * @property updatedAt Epoch-ms of the last server-side change.
 * @property readAt Epoch-ms the user read the notification; null while unread.
 * @property revision Server revision for sync convergence (digest + replay guards).
 * @property deletedAt Tombstone epoch-ms; null while the row is live.
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["deletedAt"]),
        Index(value = ["readAt"]),
    ],
)
internal data class NotificationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val eventJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val readAt: Long?,
    val revision: Long = 0,
    val deletedAt: Long? = null,
)
