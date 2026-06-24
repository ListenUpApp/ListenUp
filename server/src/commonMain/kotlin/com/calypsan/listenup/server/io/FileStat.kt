package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/** Stats [path] for size/mtime/inode, or null if it doesn't exist / can't be stat'd. JVM=java.nio, native=posix stat. */
internal expect fun statFile(path: Path): FileAttributes?
