package com.calypsan.listenup.client.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Copies an `NSData` into a Kotlin `ByteArray` in one bulk `memcpy` (no per-byte bridge). */
@OptIn(ExperimentalForeignApi::class)
fun byteArrayFromNSData(data: NSData): ByteArray {
    val length = data.length.toInt()
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        // memcpy returns the destination pointer; we only need the copy side-effect, so the
        // lambda returns Unit (explicit `return@usePinned`) and usePinned's result isn't discarded.
        memcpy(pinned.addressOf(0), data.bytes, data.length.convert())
        return@usePinned
    }
    return bytes
}

/** Copies a Kotlin `ByteArray` into an `NSData` in one bulk `memcpy` (the inverse of [byteArrayFromNSData]). */
@OptIn(ExperimentalForeignApi::class)
fun nsDataFromByteArray(bytes: ByteArray): NSData {
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
    }
}
