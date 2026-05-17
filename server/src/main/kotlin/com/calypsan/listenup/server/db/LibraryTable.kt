package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Server-internal `libraries` table. Single-bootstrap row keyed off
 * `LISTENUP_LIBRARY_PATH`. Not syncable in Books-A; future multi-library
 * support reshapes the wire to include `libraryId` per book.
 */
internal object LibraryTable : Table("libraries") {
    val id = varchar("id", 36)
    val name = varchar("name", 256)
    val rootPath = varchar("root_path", 1024)
    override val primaryKey = PrimaryKey(id)
}
