package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/** Validation-domain table for the Sync Foundation phase. */
internal object TagTable : SyncableTable("tags") {
    val id = text("id")
    val name = text("name").index()
    override val primaryKey = PrimaryKey(id)
}
