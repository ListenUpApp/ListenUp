package com.calypsan.listenup.server.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Reads the whole file at [this] as a byte array. */
internal fun Path.readBytes(): ByteArray = SystemFileSystem.source(this).buffered().use { it.readByteArray() }

/**
 * Byte twin of [readTextCapped]: reads [this] whole, or returns null when the file is larger than
 * [maxBytes] or its size cannot be determined. The size is checked before a byte is read.
 */
internal fun Path.readBytesCapped(maxBytes: Long): ByteArray? {
    val size = SystemFileSystem.metadataOrNull(this)?.size ?: return null
    if (size > maxBytes) return null
    return readBytes()
}

/** Writes [bytes] to [this], replacing any existing content. */
internal fun Path.writeBytes(bytes: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
}

/**
 * Writes [bytes] to [this] via a sibling temp file and an atomic rename, so a reader never sees a
 * half-written file and a crash mid-write leaves no truncated one behind. The parent directory must
 * already exist. The temp file is cleaned up if the write fails.
 */
internal fun Path.writeBytesAtomically(bytes: ByteArray) {
    val tmp = Path(parent!!.toString(), "$name.tmp")
    try {
        SystemFileSystem.sink(tmp).buffered().use { it.write(bytes) }
        SystemFileSystem.atomicMove(tmp, this)
    } catch (e: Throwable) {
        SystemFileSystem.delete(tmp, mustExist = false)
        throw e
    }
}
