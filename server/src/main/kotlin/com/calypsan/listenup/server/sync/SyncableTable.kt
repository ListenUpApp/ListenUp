package com.calypsan.listenup.server.sync

import org.jetbrains.exposed.v1.core.Table

/**
 * Abstract Exposed table mixin contributing the sync-discipline columns every
 * syncable domain table inherits:
 *
 *  - `revision`      monotonic, bumped on every write
 *  - `created_at`    set on insert, never modified
 *  - `updated_at`    refreshed on every write
 *  - `deleted_at`    nullable; set on soft-delete, cleared on resurrect
 *  - `client_op_id`  nullable; the most-recent write's originating op id (debug aid)
 *
 * Index on `revision` covers the hot-path REST catch-up query. Concrete domain
 * tables add their own primary key (typically `text("id")`).
 */
abstract class SyncableTable(
    name: String,
) : Table(name) {
    val revision = long("revision").index()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    val clientOpId = text("client_op_id").nullable()
}
