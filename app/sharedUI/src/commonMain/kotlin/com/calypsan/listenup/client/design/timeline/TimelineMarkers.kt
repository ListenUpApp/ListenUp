package com.calypsan.listenup.client.design.timeline

/**
 * One chapter boundary as the timeline needs to draw it.
 *
 * A view model, not the domain type: the lane cares about where a boundary sits and how it should
 * read, and nothing else about the chapter. Keeping it separate is what lets the lane be rendered
 * in a preview or a test without standing up a book.
 *
 * @property id stable identity, so a marker survives re-timing and re-sorting.
 * @property number the chapter's position for the flag label ("Ch 213").
 * @property startMs absolute start from the beginning of the book.
 * @property locked drawn with a padlock, and exempt from drift correction.
 * @property selected the one boundary the list and the lane are both focused on; drawn heavier and
 *   given the drag handle.
 */
data class TimelineChapter(
    val id: String,
    val number: Int,
    val startMs: Long,
    val locked: Boolean = false,
    val selected: Boolean = false,
)

/**
 * Where one audio file begins, drawn as a read-only reference divider.
 *
 * File boundaries are never editable — they are a fact about the media, not a decision about the
 * book — but they matter to anyone editing chapters, because a chapter that ought to start where a
 * file does is a strong hint that the scrape was right and the drift is elsewhere.
 *
 * @property label short name shown against the divider ("File 7").
 * @property startMs absolute book-time where the file begins.
 */
data class TimelineFileBoundary(
    val label: String,
    val startMs: Long,
)
