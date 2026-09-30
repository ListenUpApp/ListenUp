package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import java.nio.file.Files

internal actual fun deleteEntry(path: Path) {
    // deleteIfExists never follows a link: it removes the link itself, and it sees a dangling one.
    Files.deleteIfExists(
        java.nio.file.Path
            .of(path.toString()),
    )
}
