package com.calypsan.listenup.core

import io.ktor.utils.io.ByteReadChannel

/**
 * Apple implementation of [FileSource] backed by an in-memory [ByteArray].
 *
 * The picked backup file is read once on the Swift side — a security-scoped file URL is
 * read into `Data`, bridged to a Kotlin `ByteArray` via the `byteArrayFromNSData` bulk
 * `memcpy` helper, and handed here. Each [openChannel] call returns a fresh
 * [ByteReadChannel] over those bytes, so the multipart uploader can re-read the body if it
 * needs to (e.g. on a retry).
 *
 * Reading the whole backup into memory is acceptable for ListenUp's self-hosted scale: ABS
 * backups are a SQLite dump plus metadata, typically single-digit megabytes. If backups grow
 * large enough to warrant true streaming, swap the [bytes] backing for an `NSInputStream`
 * adapter — the [FileSource] contract (a fresh channel per call) already permits it.
 *
 * @param bytes The full file content.
 * @param filename The display filename, including the extension (e.g. `2026-06-11.audiobookshelf`).
 */
class AppleFileSource(
    private val bytes: ByteArray,
    override val filename: String,
) : FileSource {
    override val size: Long = bytes.size.toLong()

    override fun openChannel(): ByteReadChannel = ByteReadChannel(bytes)
}
