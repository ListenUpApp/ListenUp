package com.calypsan.listenup.server.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** Reads the whole file at [path] as UTF-8 text. Throws if it can't be read. */
internal fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }
