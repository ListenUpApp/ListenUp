package com.calypsan.listenup.client.presentation.player

/**
 * What a surface calls the current chapter (spec §5.7): the book's own title, never our counter, which counts front
 * matter and so can disagree with the book's naming. The mini player, the system media card, the lock screen, the
 * browser's Media Session and the tab title all say what this says.
 */
sealed interface ChapterName {
    /** The chapter's own title, as the book names it, generic ("Chapter 14") or not ("Bridge Four"). */
    data class Titled(
        val title: String,
    ) : ChapterName

    /** An untitled chapter in a chaptered book: "Chapter [number]". */
    data class Untitled(
        val number: Int,
    ) : ChapterName

    /** A book without chapters: "Whole book". */
    data object WholeBook : ChapterName
}

/** Derives [ChapterName]. An object, so Swift Export exposes it as `ChapterNaming.shared.of(...)`. */
object ChapterNaming {
    /** The name for a chapter with [title] and [number]; [isWholeBook] for a chapterless book. Pure. */
    fun of(
        title: String?,
        number: Int,
        isWholeBook: Boolean,
    ): ChapterName =
        when {
            isWholeBook -> ChapterName.WholeBook
            title.isNullOrBlank() -> ChapterName.Untitled(number)
            else -> ChapterName.Titled(title)
        }
}
