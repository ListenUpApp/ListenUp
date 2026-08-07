package com.calypsan.listenup.client.automotive

/**
 * Constants for Android Auto browse tree media IDs.
 *
 * The browse tree structure — this is what [BrowseTreeProvider] actually serves, and the two
 * are meant to stay in step. It previously also listed COLLECTIONS and BOOKMARKS nodes, which
 * no code ever built: bookmarks are not a feature ListenUp has, and collections are an
 * admin-side access-control mechanism rather than something to browse by in a car.
 * ```
 * ROOT
 * ├── RESUME (playable) - most recent in-progress book
 * └── LIBRARY (browsable)
 *     ├── RECENT - recently played books
 *     ├── DOWNLOADED - offline-available books
 *     ├── SERIES - series list
 *     └── AUTHORS - author list
 * ```
 */
object BrowseTree {
    // Root nodes
    const val ROOT = "/__root__"
    const val RESUME = "/__resume__"
    const val LIBRARY = "/__library__"

    // Library sub-nodes
    const val LIBRARY_RECENT = "/__library__/recent"
    const val LIBRARY_DOWNLOADED = "/__library__/downloaded"
    const val LIBRARY_SERIES = "/__library__/series"
    const val LIBRARY_AUTHORS = "/__library__/authors"

    // Dynamic node prefixes
    const val PREFIX_SERIES = "/__library__/series/"
    const val PREFIX_AUTHOR = "/__library__/authors/"
    const val PREFIX_BOOK = "/__book__/"

    /**
     * Extract book ID from a media ID.
     * @return Book ID or null if not a book media ID
     */
    fun extractBookId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_BOOK)) {
            mediaId.removePrefix(PREFIX_BOOK)
        } else {
            null
        }

    /**
     * Extract series ID from a media ID.
     * @return Series ID or null if not a series media ID
     */
    fun extractSeriesId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_SERIES)) {
            mediaId.removePrefix(PREFIX_SERIES)
        } else {
            null
        }

    /**
     * Extract author/contributor ID from a media ID.
     * @return Author ID or null if not an author media ID
     */
    fun extractAuthorId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_AUTHOR)) {
            mediaId.removePrefix(PREFIX_AUTHOR)
        } else {
            null
        }

    /**
     * Create a book media ID.
     */
    fun bookId(bookId: String): String = "$PREFIX_BOOK$bookId"

    /**
     * Create a series media ID.
     */
    fun seriesId(seriesId: String): String = "$PREFIX_SERIES$seriesId"

    /**
     * Create an author media ID.
     */
    fun authorId(authorId: String): String = "$PREFIX_AUTHOR$authorId"
}
