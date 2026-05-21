package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
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

    /**
     * The operator-configured textual-metadata precedence serialized as a
     * comma-separated source list. The running scanner uses the env-resolved
     * `LISTENUP_METADATA_PRECEDENCE` value, threaded directly to the `Analyzer`.
     * This column persists that value on the `libraries` row as forward-storage
     * for the future per-library Libraries domain phase; it is not read back today.
     */
    val metadataPrecedence = varchar("metadata_precedence", 256).default(MetadataPrecedence.DEFAULT.serialize())
    override val primaryKey = PrimaryKey(id)
}
