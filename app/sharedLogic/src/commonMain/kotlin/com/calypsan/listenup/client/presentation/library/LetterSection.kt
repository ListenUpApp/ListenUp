package com.calypsan.listenup.client.presentation.library

/**
 * One run of a sorted Library list that files under a single index letter (spec §3.1.1 L25/L26).
 *
 * Indexes refer to the list the section was derived from (`Loaded.books`, `.series`, `.authors`,
 * `.narrators`). Sections are in list order, so a descending sort runs Z→A. A letter MAY recur when
 * the sort key's order disagrees with the letter rule (books with no series sort last but file under
 * `#`, like numeric series names that sort first): every run gets a header, and a jump rail takes each
 * letter's FIRST section.
 *
 * @property letter The index letter: an uppercase letter, or `#` for a blank, numeric or symbolic key.
 * @property firstIndex Where the run starts in its list.
 * @property count How many items the run holds.
 */
data class LetterSection(
    val letter: String,
    val firstIndex: Int,
    val count: Int,
)
