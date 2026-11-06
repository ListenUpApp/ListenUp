package com.calypsan.listenup.server.media

/** `<sha256 hex>.<ImageKind ext>` — the file name every upload route and the matcher mint. */
private val MANAGED_IMAGE_NAME =
    Regex("[0-9a-f]{64}\\.(${ImageKind.entries.joinToString("|") { it.ext }})")

/**
 * Whether [relativePath] is a path the server itself minted for an image under [folder]:
 * exactly `<folder>/<sha256 hex>.<image ext>`. The one definition of a managed image path —
 * stored paths are checked against it when they are written and again when they are served.
 */
internal fun isManagedImagePath(
    folder: String,
    relativePath: String,
): Boolean = relativePath.removePrefix("$folder/").let { it != relativePath && MANAGED_IMAGE_NAME.matches(it) }
