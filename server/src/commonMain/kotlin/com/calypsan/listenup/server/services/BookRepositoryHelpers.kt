package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.io.hashBytesSha256

/** Returns the SHA-256 hex digest of [this] byte array. */
internal fun ByteArray.sha256Hex(): String = hashBytesSha256(this)

/** Maps a wire `Boolean` to the SQLite `0/1` INTEGER the books table stores. */
internal fun Boolean.toDbLong(): Long = if (this) 1L else 0L
