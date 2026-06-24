package com.calypsan.listenup.server.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Reads the whole file at [this] as a byte array. */
internal fun Path.readBytes(): ByteArray = SystemFileSystem.source(this).buffered().use { it.readByteArray() }

/** Writes [bytes] to [this], replacing any existing content. */
internal fun Path.writeBytes(bytes: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
}
