package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * Removes the directory entry at [path] itself, never following a symbolic link: a link is
 * unlinked (dangling, looping, or pointing anywhere at all), a file is unlinked, an empty directory
 * is removed. A missing entry is a no-op. Throws on anything else, such as a non-empty directory.
 *
 * Exists because kotlinx-io's `SystemFileSystem.delete` opens with `exists(path)`, which follows
 * links: a dangling link reads as absent and is silently left behind.
 * JVM = `Files.deleteIfExists`, native = `lstat` + `unlink`/`rmdir`.
 */
internal expect fun deleteEntry(path: Path)
