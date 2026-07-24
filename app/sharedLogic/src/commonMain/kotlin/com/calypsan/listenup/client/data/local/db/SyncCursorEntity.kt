package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-domain cursor for the client sync engine. One row per registered domain;
 * value is the highest revision the client has fully applied (from either the firehose
 * tail or REST catch-up). Persisted across app restarts so reconnect can
 * resume via `Last-Event-Id` instead of falling back to REST every time.
 */
@Entity(tableName = "sync_cursor")
internal data class SyncCursorEntity(
    @PrimaryKey val domainName: String,
    val revision: Long,
)
