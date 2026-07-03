package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook

/**
 * The cover-identity fields a book tile needs, bundled so they always travel together — a call-site
 * cannot silently omit [coverHash] (whose absence collapses the Coil cache key and serves a stale
 * cover). Per-surface state (progress, finished, playing) stays as separate `BookCard` params.
 */
data class BookCoverModel(
    val bookId: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val coverHash: String?,
    val blurHash: String?,
)

/** Bundle a library/shelf book's cover identity for a [com.calypsan.listenup.client.features.library.BookCard]. */
fun BookListItem.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = id.value,
        title = title,
        author = authorNames,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = coverBlurHash,
    )

/** Bundle a "Recently Added" Discover tile's cover identity. */
fun RecentlyAddedUiBook.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = id,
        title = title,
        author = authorName,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = coverBlurHash,
    )

/** Bundle a "Discover Something New" tile's cover identity. */
fun DiscoverUiBook.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = id,
        title = title,
        author = authorName,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = coverBlurHash,
    )

/** Bundle a search hit's cover identity. Search hits carry no blurHash. */
fun SearchHit.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = id,
        title = name,
        author = author,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = null,
    )

/** Bundle a shelf book's cover identity. */
fun ShelfBook.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = id.value,
        title = title,
        author = authorNames.joinToString(", "),
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = null,
    )

/** Bundle a "Continue Listening" book's cover identity. */
fun ContinueListeningBook.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = bookId,
        title = title,
        author = authorNames,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = coverBlurHash,
    )

/** Bundle a Discover "currently listening" session's cover identity. */
fun CurrentlyListeningUiSession.toCoverModel(): BookCoverModel =
    BookCoverModel(
        bookId = bookId,
        title = bookTitle,
        author = authorName,
        coverPath = coverPath,
        coverHash = coverHash,
        blurHash = coverBlurHash,
    )
