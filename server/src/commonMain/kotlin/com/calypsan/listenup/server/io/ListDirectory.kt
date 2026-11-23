package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * The entries of [directory], or a thrown `kotlinx.io.IOException` when it cannot be listed.
 *
 * `SystemFileSystem.list` already throws on the native server, but on the JVM it lists a directory
 * it may not read as empty (`java.io.File.list` returns null and the library maps that to no
 * children). A walker cannot tell "empty" from "unreadable" through it, and the scanner treats a
 * book it did not walk very differently from a book that is gone.
 */
internal expect fun listDirectory(directory: Path): List<Path>
