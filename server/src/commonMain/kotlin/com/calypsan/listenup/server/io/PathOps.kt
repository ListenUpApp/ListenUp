package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * Resolves [path] to an absolute path with `.`/`..` collapsed. The platforms differ on links: the
 * JVM (`toAbsolutePath().normalize()`) is purely lexical and does NOT resolve symbolic links, while
 * native (`realpath(3)`) does, and needs the path to exist. Not a containment primitive — see the
 * library-write broker's `resolvedForContainment` for that.
 */
internal expect fun canonicalize(path: Path): Path
