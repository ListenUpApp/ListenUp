package com.calypsan.listenup.server.metadata.spi

/**
 * One chapter marker in a provider-neutral chapter list.
 *
 * [startMs] is the offset from the start of the book; [lengthMs] is the chapter's
 * duration when the catalog provides it (`null` when only start markers are known,
 * e.g. the next chapter's start implies this one's end). [title] is `null` for
 * unnamed markers.
 */
data class ChapterMeta(
    /** Chapter title, or `null` for an unnamed marker. */
    val title: String? = null,
    /** Offset from the start of the book, in milliseconds. */
    val startMs: Long,
    /** Chapter duration in milliseconds, when known. */
    val lengthMs: Long? = null,
)

/**
 * A provider-neutral chapter list for one book.
 *
 * [accurate] flags whether the markers are catalog-verified (true) or heuristic
 * (false) — the router prefers an accurate list. [brandIntroMs] / [brandOutroMs]
 * carry the publisher intro/outro padding a catalog reports so a later step can
 * offset markers against the local file's own intro/outro.
 */
data class ChapterListMeta(
    /** The chapter markers, in playback order. */
    val chapters: List<ChapterMeta>,
    /** Whether these markers are catalog-verified rather than heuristic. */
    val accurate: Boolean,
    /** Publisher intro padding in milliseconds, when reported. */
    val brandIntroMs: Long? = null,
    /** Publisher outro padding in milliseconds, when reported. */
    val brandOutroMs: Long? = null,
)
