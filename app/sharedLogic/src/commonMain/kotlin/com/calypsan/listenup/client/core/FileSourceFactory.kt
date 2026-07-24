package com.calypsan.listenup.client.core

import com.calypsan.listenup.core.ByteArrayFileSource
import com.calypsan.listenup.core.FileSource

/**
 * Wrap already-read [bytes] in an in-memory [FileSource].
 *
 * The shared, platform-agnostic way to build a [FileSource] from a byte buffer, and the
 * construction path Swift export exposes to iOS. It lives in `:app:sharedLogic` (a fully-exported
 * module) rather than `:contract` (exported transitively — types only, no top-level functions),
 * so non-Kotlin callers can reach it; the concrete [ByteArrayFileSource] backing is an
 * implementation detail Swift export prunes from its surface.
 *
 * @param bytes The full file content.
 * @param filename The display filename, including the extension.
 */
fun fileSourceOf(
    bytes: ByteArray,
    filename: String,
): FileSource = ByteArrayFileSource(bytes, filename)
