package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * A Book-tier grouping within a chapter set.
 *
 * [title] is null for the implicit group holding chapters that precede any Book header — a
 * Part-only book, or a preface that sits outside the structure.
 */
data class BookGroup(
    val title: String?,
    val parts: List<PartGroup>,
)

/**
 * A Part-tier grouping within a [BookGroup].
 *
 * [title] is null for the implicit group holding chapters that precede any Part header within
 * their book — a leading intro chapter, say.
 */
data class PartGroup(
    val title: String?,
    val chapters: List<Chapter>,
)

/**
 * Groups an ordered chapter list into its Book → Part → Chapter hierarchy.
 *
 * A header is an attribute of the chapter that *opens* the group: a non-null [Chapter.bookTitle]
 * starts a new [BookGroup], a non-null [Chapter.partTitle] starts a new [PartGroup] within the
 * current book. Membership is derived purely from order — there are no parent pointers and nothing
 * is stored — so moving one chapter never requires rewriting its siblings.
 *
 * Pure and total. A header-free list yields a single `BookGroup(null)` holding a single
 * `PartGroup(null)` holding every chapter: the flat case, which is most books, renders exactly as
 * it does today with no special-casing at the call site. The receiver is assumed already sorted by
 * [Chapter.startTime].
 */
fun List<Chapter>.groupChapters(): List<BookGroup> {
    val books = mutableListOf<BookGroup>()
    var parts = mutableListOf<PartGroup>()
    var chapters = mutableListOf<Chapter>()
    var bookTitle: String? = null
    var partTitle: String? = null

    fun flushPart() {
        if (chapters.isNotEmpty()) {
            parts.add(PartGroup(partTitle, chapters))
            chapters = mutableListOf()
        }
        partTitle = null
    }

    fun flushBook() {
        flushPart()
        if (parts.isNotEmpty()) {
            books.add(BookGroup(bookTitle, parts))
            parts = mutableListOf()
        }
        bookTitle = null
    }

    for (chapter in this) {
        if (chapter.bookTitle != null) {
            flushBook()
            bookTitle = chapter.bookTitle
        }
        if (chapter.partTitle != null) {
            flushPart()
            partTitle = chapter.partTitle
        }
        chapters.add(chapter)
    }
    flushBook()
    return books
}
