package com.calypsan.listenup.server.librarywrite

import kotlinx.io.files.Path
import java.nio.file.Files

internal actual fun createSymbolicLink(
    link: Path,
    target: Path,
) {
    Files.createSymbolicLink(
        java.nio.file.Path
            .of(link.toString()),
        java.nio.file.Path
            .of(target.toString()),
    )
}
