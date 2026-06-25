@file:OptIn(ExperimentalStdlibApi::class)

package com.calypsan.listenup.server.io

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val sha256Hasher = CryptographyProvider.Default.get(SHA256).hasher()

/** Lowercase 64-char hex SHA-256 of [bytes]. */
internal fun hashBytesSha256(bytes: ByteArray): String = sha256Hasher.hashBlocking(bytes).toHexString()

/** Lowercase 64-char hex SHA-256 of the file at [path], streamed (the file is never fully loaded). */
internal fun hashFileSha256(path: Path): String =
    SystemFileSystem.source(path).use { source -> sha256Hasher.hashBlocking(source).toByteArray().toHexString() }
