@file:OptIn(ExperimentalStdlibApi::class)

package com.calypsan.listenup.server.io

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val sha256Hasher = CryptographyProvider.Default.get(SHA256).hasher()

/** Lowercase 64-char hex SHA-256 of [bytes]. */
internal fun hashBytesSha256(bytes: ByteArray): String = sha256Hasher.hashBlocking(bytes).toHexString()

/** Lowercase 64-char hex SHA-256 of the file at [path], streamed (the file is never fully loaded). */
internal fun hashFileSha256(path: Path): String =
    SystemFileSystem.source(path).use { source -> sha256Hasher.hashBlocking(source).toByteArray().toHexString() }

/** Lowercase 64-char hex SHA-256 of all bytes drained from [source] (one-shot, streamed). */
internal fun hashSourceSha256(source: RawSource): String =
    sha256Hasher.hashBlocking(source).toByteArray().toHexString()

/**
 * Incremental SHA-256 — fed bytes across multiple [update] calls, finalized once via [digestHex].
 * Follows the update/finalize shape of [com.calypsan.listenup.server.compression.Crc32]. Single-use:
 * [digestHex] releases the underlying hash function, so do not call [update] after it.
 */
internal class Sha256 {
    private val fn = sha256Hasher.createHashFunction()

    fun update(bytes: ByteArray) = fn.update(bytes)

    /** Finalizes and returns the lowercase 64-char hex digest, releasing the function. */
    fun digestHex(): String = fn.use { it.hashToByteArray().toHexString() }
}
