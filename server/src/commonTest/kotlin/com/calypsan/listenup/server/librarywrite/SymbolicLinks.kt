package com.calypsan.listenup.server.librarywrite

import kotlinx.io.files.Path

/**
 * Creates a symbolic link at [link] pointing at [target], which need not exist — a dangling link
 * and a link to itself are both fixtures the containment specs need. Test-only: production code
 * never makes links, it only has to survive the ones users make.
 */
internal expect fun createSymbolicLink(
    link: Path,
    target: Path,
)
