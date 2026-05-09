package com.calypsan.listenup.server.sync

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Single-row counter table for the global sync revision space. */
internal object SyncMetaTable : Table("sync_meta") {
    val key = text("key")
    val value = long("value")
    override val primaryKey = PrimaryKey(key)
}

private const val COUNTER_KEY = "revision_counter"

/**
 * Bumps and returns the next global revision. Must be called from within an
 * open transaction — the single-row UPDATE serializes concurrent writes,
 * which is the desired behavior — every syncable write gets a unique,
 * monotonic revision.
 */
internal fun JdbcTransaction.nextRevision(): Long {
    val current =
        SyncMetaTable
            .selectAll()
            .where { SyncMetaTable.key eq COUNTER_KEY }
            .single()[SyncMetaTable.value]
    val next = current + 1
    SyncMetaTable.update({ SyncMetaTable.key eq COUNTER_KEY }) {
        it[value] = next
    }
    return next
}
