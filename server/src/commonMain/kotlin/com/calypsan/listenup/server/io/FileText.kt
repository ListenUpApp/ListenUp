package com.calypsan.listenup.server.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** Reads the whole file at [path] as UTF-8 text. Throws if it can't be read. */
internal fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

/** Writes [text] to [this] path as UTF-8, truncating any existing content (no append). Throws if it can't be written. */
internal fun Path.writeText(text: String): Unit = SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
