package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * Best-effort restriction of [path] to owner read/write only (`0600`); a no-op where the filesystem
 * doesn't support POSIX permissions. JVM = `setPosixFilePermissions`; native = `chmod`.
 */
internal expect fun restrictFileToOwner(path: Path)
