package com.calypsan.listenup.server.media

/**
 * The directories under the data home that hold images the server owns. A backup archives each of
 * them and the orphan sweep prunes each of them, so both read this list: a directory added here is
 * backed up and swept, and one missing here is neither.
 */
internal object ManagedImageDirs {
    const val COVERS = "covers"
    const val AVATARS = "avatars"
    const val CONTRIBUTORS = "contributors"
    const val SERIES = "series"

    /** Archive order. Stable: the per-directory checksum is taken in this order on both sides. */
    val ALL: List<String> = listOf(COVERS, AVATARS, CONTRIBUTORS, SERIES)
}
