package com.calypsan.listenup.client.design.util

/**
 * Last-modified time (epoch millis) of the file at [path], or null when the file
 * does not exist or cannot be stat'd.
 *
 * Used to derive a content-versioned image cache key so a file overwritten at a
 * stable path busts Coil's memory/disk cache. File metadata is a platform API, so
 * this lives behind `expect`/`actual` — keeping `commonMain` free of `java.io`.
 *
 * - Android / Desktop (JVM): `java.io.File.lastModified()`.
 */
internal expect fun fileLastModifiedMillis(path: String): Long?
