package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * Syncable table for tags.
 *
 * [id] is the stable storage row identity (UUIDv7 string). [slug] is the canonical
 * URL-safe identity derived from [name] at creation time — immutable thereafter, so
 * renames update [name] only. A partial unique index on [slug] (where `deleted_at IS NULL`)
 * enforces uniqueness among live tags while allowing slug reuse after a tag is soft-deleted.
 */
internal object TagTable : SyncableTable("tags") {
    val id = text("id")
    val name = text("name").index()
    val slug = text("slug").uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}
