package com.calypsan.listenup.web

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

/**
 * [FileReader] as a suspend call; null on a read error rather than an exception.
 *
 * A read failure is a browser-local dead end — there is no `AppError` for "your own disk refused" —
 * so every caller treats null the same way: log it, drop the pick, leave the form untouched so the
 * reader can simply pick again.
 *
 * Shared rather than per-feature because a picked file becomes bytes exactly one way, and two
 * copies of a `FileReader` dance would be two places to get the error path wrong.
 */
internal suspend fun File.readByteArray(): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        val reader = FileReader()
        reader.onload = {
            val buffer = reader.result.unsafeCast<ArrayBuffer>()
            continuation.resume(Int8Array(buffer).unsafeCast<ByteArray>())
        }
        reader.onerror = {
            console.error("File could not be read: $name")
            continuation.resume(null)
        }
        reader.readAsArrayBuffer(this)
    }
