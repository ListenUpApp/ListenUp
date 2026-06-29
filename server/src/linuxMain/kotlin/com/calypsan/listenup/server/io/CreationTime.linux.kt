package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

// posix stat exposes no portable creation (birth) time; 0L is the documented sort-fallback floor.
internal actual fun creationTimeMillis(path: Path): Long = 0L
