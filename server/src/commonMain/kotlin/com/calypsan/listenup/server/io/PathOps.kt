package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * Resolves [path] to a canonical absolute path (collapses `..`/symlinks). The path must exist.
 * JVM=toAbsolutePath().normalize(), native=realpath(3).
 */
internal expect fun canonicalize(path: Path): Path
