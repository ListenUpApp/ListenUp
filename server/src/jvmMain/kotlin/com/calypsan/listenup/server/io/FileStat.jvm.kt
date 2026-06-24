package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Matches the `ino=N` segment of a Linux `fileKey()` (`(dev=…,ino=N)`). */
private val INODE_PATTERN = Regex("""ino=(\d+)""")

internal actual fun statFile(path: Path): FileAttributes? =
    runCatching {
        val nioPath =
            java.nio.file.Path
                .of(path.toString())
        val attrs = Files.readAttributes(nioPath, BasicFileAttributes::class.java)
        FileAttributes(
            size = attrs.size(),
            mtimeMs = attrs.lastModifiedTime().toMillis(),
            inode = inodeOf(attrs),
        )
    }.getOrNull()

private fun inodeOf(attrs: BasicFileAttributes): Long? =
    attrs
        .fileKey()
        ?.toString()
        ?.let {
            INODE_PATTERN
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        }
