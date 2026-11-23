package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import java.nio.file.Files

// `Files.newDirectoryStream` throws (AccessDeniedException, a java.io.IOException) where
// `java.io.File.list` would return null.
internal actual fun listDirectory(directory: Path): List<Path> =
    Files
        .newDirectoryStream(
            java.nio.file.Path
                .of(directory.toString()),
        ).use { entries ->
            entries.map { Path(directory, it.fileName.toString()) }
        }
