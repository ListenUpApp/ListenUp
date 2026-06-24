package com.calypsan.listenup.server.io

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Path as NioPath

private val logger = KotlinLogging.logger {}

internal actual fun restrictFileToOwner(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(
            NioPath.of(path.toString()),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }.onFailure { logger.debug(it) { "Could not restrict permissions on $path" } }
}
