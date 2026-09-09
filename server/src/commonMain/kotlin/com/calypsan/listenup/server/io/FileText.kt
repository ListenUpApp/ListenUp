package com.calypsan.listenup.server.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Ceiling on a sidecar read. A sidecar carries a book's metadata — a title, a description, a
 * contributor list — so a megabyte is already far more than any real one holds. The files
 * themselves come from wherever the operator's library came from, and reading one whole into
 * memory is the only thing standing between a mislabelled media file and the server's heap.
 */
internal const val SIDECAR_MAX_BYTES = 1L * 1024 * 1024

/** Reads the whole file at [path] as UTF-8 text. Throws if it can't be read. */
internal fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

/**
 * Reads [this] as UTF-8 text, or returns null when the file is larger than [maxBytes] or its size
 * cannot be determined. The size is checked before a byte is read, so an oversized file costs a
 * stat rather than its own length in memory.
 */
internal fun Path.readTextCapped(maxBytes: Long): String? {
    val size = SystemFileSystem.metadataOrNull(this)?.size ?: return null
    if (size > maxBytes) return null
    return readText()
}

/** Writes [text] to [this] path as UTF-8, truncating any existing content (no append). Throws if it can't be written. */
internal fun Path.writeText(text: String): Unit = SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
