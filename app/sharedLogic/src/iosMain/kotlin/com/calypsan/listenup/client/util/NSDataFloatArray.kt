package com.calypsan.listenup.client.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Copies an `NSData` of native-endian 32-bit floats into a Kotlin `FloatArray` in one bulk
 * `memcpy` (no per-sample bridge).
 *
 * Swift Export cannot construct a `FloatArray` from Swift — every generated initializer is
 * `fatalError()` — and filling one through the generated `_set` would cost one bridge crossing
 * per sample. So PCM crosses as `NSData`, exactly as image bytes cross via [byteArrayFromNSData].
 */
@OptIn(ExperimentalForeignApi::class)
fun floatArrayFromNSData(data: NSData): FloatArray {
    val count = data.length.toInt() / Float.SIZE_BYTES
    if (count == 0) return FloatArray(0)
    val floats = FloatArray(count)
    floats.usePinned { pinned ->
        // memcpy returns the destination pointer; we only need the copy side-effect, so the
        // lambda returns Unit (explicit `return@usePinned`) and usePinned's result isn't discarded.
        memcpy(pinned.addressOf(0), data.bytes, (count * Float.SIZE_BYTES).convert())
        return@usePinned
    }
    return floats
}
