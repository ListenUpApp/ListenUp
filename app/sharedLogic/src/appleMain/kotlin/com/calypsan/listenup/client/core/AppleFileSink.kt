package com.calypsan.listenup.client.core

import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [RawSink] that writes to the file at [path], replacing it if it exists — the construction path
 * Swift export gives iOS for downloads, the counterpart of [fileSourceAtPath] for uploads.
 *
 * A backup archive can be several gigabytes, so the bytes stream to disk as they arrive and never
 * pass through memory as a whole. The receiver owns the sink and closes it (the shared
 * `AdminBackupViewModel.downloadBackup` does, even when the download is cancelled).
 *
 * @param path An absolute filesystem path whose parent directory exists.
 * @throws IOException when the file cannot be created, e.g. its directory is missing. Declared with
 * [Throws] so Swift sees a `throws` function: an undeclared Kotlin exception would trap the process.
 */
@Throws(IOException::class)
fun fileSinkAtPath(path: String): RawSink = SystemFileSystem.sink(Path(path))
