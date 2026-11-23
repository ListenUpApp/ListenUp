package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// kotlinx-io's native `list` throws when `opendir` fails, which is the contract already.
internal actual fun listDirectory(directory: Path): List<Path> = SystemFileSystem.list(directory).toList()
