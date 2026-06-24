package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * True if [path] is a symbolic link. Does **not** follow the link — a symlink to a directory
 * still returns true (so the Walker can treat it as a leaf and avoid descending into cycles).
 * JVM = `Files.isSymbolicLink`, native = `lstat` + `S_IFLNK`.
 */
internal expect fun isSymlink(path: Path): Boolean
