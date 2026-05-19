package com.calypsan.listenup.client.upload

import com.calypsan.listenup.core.FileSource
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.File
import java.io.FileInputStream

/**
 * [FileSource] implementation backed by a [java.io.File] in the app cache directory.
 *
 * Used by [ABSUploadWorker] to stream a previously-cached file during background upload.
 * The file must already exist on disk (copied from the content URI while in the foreground).
 *
 * @param file The cached file on disk
 * @param name The original display filename
 */
class CachedFileSource(
    private val file: File,
    private val name: String,
) : FileSource {
    override val filename: String get() = name
    override val size: Long? get() = file.length()

    override fun openChannel(): ByteReadChannel = FileInputStream(file).toByteReadChannel()
}
