package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.getenv
import platform.posix.gethostname

@OptIn(ExperimentalForeignApi::class)
internal actual fun readEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun userHomeDir(): String = getenv("HOME")?.toKString().orEmpty()

@OptIn(ExperimentalForeignApi::class)
internal actual fun hostname(): String {
    // HOST_NAME_MAX is 64; 256 is comfortably above it. gethostname may omit the NUL on truncation,
    // so decode up to the first NUL (or the whole buffer if none).
    val buffer = ByteArray(256)
    val result = buffer.usePinned { gethostname(it.addressOf(0), buffer.size.convert()) }
    if (result != 0) return ""
    val end = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
    return buffer.decodeToString(0, end)
}
