package com.calypsan.listenup.server.db

/**
 * One versioned migration: parsed from a `V<version>__<name>.sql` file at build time.
 * [checksum] is a SHA-256 of the file content, used to detect edits to applied migrations.
 */
internal data class Migration(
    val version: Int,
    val name: String,
    val checksum: String,
    val sql: String,
)
