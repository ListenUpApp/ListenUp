package com.calypsan.listenup.web.features.contributordetail

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.RoleSection
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A loaded contributor, for specs about the hero, role panels and series cards.
 *
 * Lives here rather than in the page it drives — the same split [readyBook][com.calypsan.listenup.web.features.bookdetail.readyBook]
 * makes for Book Detail — so the page renders whatever the shared ViewModel gives it, and this
 * fixture is not something a user could reach.
 *
 * [bookCount] and [totalDuration] default to values that DON'T match any role section's
 * `previewBooks` — the real ViewModel derives them from every distinct book across all roles, not
 * from the previews, so a fixture that let the two agree would hide a regression where the page
 * quietly summed the previews instead of trusting the stat.
 */
internal fun readyContributor(
    name: String = "Stephen King",
    roleSections: List<RoleSection> = listOf(roleSection()),
    bookProgress: Map<BookId, Float> = emptyMap(),
    bookCreditedAs: Map<String, String> = emptyMap(),
    series: List<SeriesWithBooks> = emptyList(),
    bookCount: Int = DEFAULT_BOOK_COUNT,
    totalDuration: Duration = DEFAULT_TOTAL_DURATION,
): ContributorDetailUiState.Ready =
    ContributorDetailUiState.Ready(
        contributor = Contributor(id = ContributorId("c-king"), name = name),
        roleSections = roleSections,
        bookProgress = bookProgress,
        bookCreditedAs = bookCreditedAs,
        series = series,
        bookCount = bookCount,
        totalDuration = totalDuration,
        isDeleting = false,
        deleteError = null,
    )

/**
 * One role's worth of books, for panel specs. [bookCount] defaults to more than [previewBooks]
 * ever holds — the ViewModel's own contract (`previewBooks` caps at 10; `bookCount` is the true
 * total) — so a spec built from the default catches a panel that shows the preview length instead
 * of the real count.
 */
internal fun roleSection(
    role: String = ContributorRole.AUTHOR.apiValue,
    displayName: String = "Written By",
    bookCount: Int = DEFAULT_ROLE_BOOK_COUNT,
    previewBooks: List<BookListItem> = listOf(bookItem("b1", "The Institute")),
): RoleSection =
    RoleSection(
        role = role,
        displayName = displayName,
        bookCount = bookCount,
        previewBooks = previewBooks,
    )

/** A minimal list-shaped book, enough to drive a tile: id, title, and a duration. */
internal fun bookItem(
    id: String,
    title: String,
    durationMs: Long = DEFAULT_BOOK_DURATION_MS,
): BookListItem =
    BookListItem(
        id = BookId(id),
        libraryId = LibraryId("library-1"),
        folderId = FolderId("folder-1"),
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = durationMs,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

/** A series with two books in sequence order, for the series panel. */
internal fun seriesWithBooks(
    id: String = "s1",
    name: String = "The Dark Tower",
    books: List<BookListItem> = listOf(bookItem("b1", "The Gunslinger"), bookItem("b2", "The Drawing of the Three")),
): SeriesWithBooks =
    SeriesWithBooks(
        series = Series(id = SeriesId(id), name = name),
        books = books,
        bookSequences = books.mapIndexed { index, book -> book.id.value to (index + 1).toDouble() }.toMap(),
    )

private const val DEFAULT_BOOK_COUNT = 64

private val DEFAULT_TOTAL_DURATION = 892.hours

private const val DEFAULT_ROLE_BOOK_COUNT = 58

private const val DEFAULT_BOOK_DURATION_MS = 9L * 3600 * 1000
