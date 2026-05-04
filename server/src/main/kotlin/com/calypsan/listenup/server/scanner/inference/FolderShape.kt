package com.calypsan.listenup.server.scanner.inference

/**
 * What ABS calls "folder structure" — the bottom three components of a
 * relative path map to `<author>/<series>/<title>`, with author and series
 * optional. Ported from
 * `audiobookshelf/server/utils/scandir.js:149-173`.
 *
 * Components above the bottom three are ignored. A single-level path
 * (a file or folder directly in the library root) yields a title-only
 * shape.
 */
data class FolderShape(
    val titleFolder: String,
    val seriesFolder: String? = null,
    val authorFolder: String? = null,
) {
    companion object {
        fun parse(relPathToBook: String): FolderShape {
            val parts =
                relPathToBook
                    .replace('\\', '/')
                    .trim('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .toMutableList()

            if (parts.isEmpty()) return FolderShape(titleFolder = "")

            val title = parts.removeLast()
            val series = if (parts.size >= 2) parts.removeLast() else null
            val author = if (parts.isNotEmpty()) parts.removeLast() else null

            return FolderShape(
                titleFolder = title,
                seriesFolder = series,
                authorFolder = author,
            )
        }
    }
}
