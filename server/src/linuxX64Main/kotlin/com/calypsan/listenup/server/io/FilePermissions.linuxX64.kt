@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod

internal actual fun restrictFileToOwner(path: Path) {
    // Best-effort 0600, mirroring the JVM actual; ignore the result on unsupported filesystems.
    chmod(path.toString(), (S_IRUSR or S_IWUSR).toUInt())
}
