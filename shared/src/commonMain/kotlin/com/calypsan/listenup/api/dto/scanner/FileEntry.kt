package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.Serializable

/**
 * One file the Walker discovered. Path is relative to the library root and
 * uses POSIX separators regardless of the OS that produced it — clients on
 * Linux, macOS, and Windows all see the same shape.
 *
 * `inode` is null when the underlying filesystem doesn't expose stable file
 * keys (Windows default, FAT, some SMB mounts). The Differ degrades to
 * path-only matching when inode is unavailable; cross-folder move detection
 * is the feature that suffers.
 */
@Serializable
data class FileEntry(
    val relPath: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtimeMs: Long,
    val inode: Long? = null,
    val fileType: FileType,
)

/**
 * Classification the Walker assigns to each [FileEntry] based on extension. Drives downstream
 * routing — audio files become [TrackEntry]s, images become candidate covers, the rest are
 * carried for diagnostics but ignored by the Differ.
 */
@Serializable
enum class FileType {
    AUDIO,
    IMAGE,
    EBOOK,
    METADATA,
    TEXT,
    UNKNOWN,
}
