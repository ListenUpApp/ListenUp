package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import java.nio.file.Files

internal actual fun isSymlink(path: Path): Boolean =
    Files.isSymbolicLink(
        java.nio.file.Path
            .of(path.toString()),
    )
