package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal actual fun creationTimeMillis(path: Path): Long =
    Files
        .readAttributes(
            java.nio.file.Path
                .of(path.toString()),
            BasicFileAttributes::class.java,
        ).creationTime()
        .toMillis()
