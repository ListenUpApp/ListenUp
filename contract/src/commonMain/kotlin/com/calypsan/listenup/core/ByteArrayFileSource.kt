package com.calypsan.listenup.core

import io.ktor.utils.io.ByteReadChannel

/**
 * In-memory [FileSource] backed by a [ByteArray].
 *
 * Platform-agnostic: the content is read once by the caller (e.g. an iOS security-scoped file URL
 * read into `Data` and bridged to a Kotlin `ByteArray`, or any platform's bytes) and handed here.
 * Each [openChannel] call returns a fresh [ByteReadChannel] over those bytes, so the multipart
 * uploader can re-read the body if it needs to (e.g. on a retry).
 *
 * Holding the whole file in memory is acceptable for ListenUp's self-hosted scale: ABS backups are
 * a SQLite dump plus metadata, typically single-digit megabytes. Platforms that can stream large
 * files lazily (Android's `ContentResolver`) have their own [FileSource]; this is the simple,
 * shared fallback.
 *
 * Non-Kotlin callers (iOS) construct one via `fileSourceOf` in `:app:sharedLogic` rather than this
 * constructor directly — Swift export bridges `:contract` in transitive mode (types only, no
 * top-level functions), so the Swift-callable factory has to live in the fully-exported module.
 *
 * @param bytes The full file content.
 * @param filename The display filename, including the extension (e.g. `2026-06-11.audiobookshelf`).
 */
class ByteArrayFileSource(
    private val bytes: ByteArray,
    override val filename: String,
) : FileSource {
    override val size: Long = bytes.size.toLong()

    override fun openChannel(): ByteReadChannel = ByteReadChannel(bytes)
}
