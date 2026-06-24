package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

internal actual fun canonicalize(path: Path): Path =
    Path(
        java.nio.file.Path
            .of(path.toString())
            .toAbsolutePath()
            .normalize()
            .toString(),
    )
