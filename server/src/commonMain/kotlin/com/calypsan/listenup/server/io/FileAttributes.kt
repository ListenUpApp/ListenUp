package com.calypsan.listenup.server.io

/** File attributes not exposed by kotlinx-io's [kotlinx.io.files.FileMetadata]: mtime + inode. */
internal data class FileAttributes(
    val size: Long,
    val mtimeMs: Long,
    val inode: Long?,
)
