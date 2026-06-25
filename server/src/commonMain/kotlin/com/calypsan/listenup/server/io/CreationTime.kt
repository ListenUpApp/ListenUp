package com.calypsan.listenup.server.io

import kotlinx.io.files.Path

/**
 * Wall-clock file creation time of [path] in epoch milliseconds.
 *
 * JVM = `BasicFileAttributes.creationTime()`. Native = `0L` — posix `stat` exposes no portable
 * creation (birth) time (`st_ctim` is *change* time, not birth time). Callers use this only as a
 * sort fallback when a richer timestamp (e.g. an upload-time `meta.json`) is absent, so a `0L`
 * floor on native is acceptable. May throw on JVM if [path] does not exist; callers wrap accordingly.
 */
internal expect fun creationTimeMillis(path: Path): Long
