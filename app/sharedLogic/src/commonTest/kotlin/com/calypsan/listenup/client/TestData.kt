package com.calypsan.listenup.client

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag

/**
 * Shared test data factory for creating domain objects in tests.
 *
 * Provides sensible defaults for all properties while allowing
 * customization of specific fields.
 *
 * Usage:
 * ```
 * val book = TestData.bookListItem(title = "Custom Title")
 * val contributor = TestData.contributor(name = "Jane Doe")
 * ```
 */
object TestData {
    /**
     * Creates a sample [BookDetail] with sensible defaults.
     *
     * Mirrors [book] so existing test sites can flip from `Book` to `BookDetail`
     * with a one-word rename. The [genres] and [tags] arguments default to
     * empty — pass them when the test exercises detail-screen genre/tag flows.
     */
    fun bookDetail(
        id: String = "book-1",
        title: String = "The Great Gatsby",
        subtitle: String? = null,
        authorName: String = "F. Scott Fitzgerald",
        narratorName: String = "Jake Gyllenhaal",
        allContributors: List<BookContributor>? = null,
        duration: Long = 5_400_000L,
        coverPath: String? = "/covers/gatsby.jpg",
        description: String? = "A story of decadence and excess in the Jazz Age.",
        genres: List<Genre> = emptyList(),
        tags: List<Tag> = emptyList(),
        seriesId: String? = null,
        seriesName: String? = null,
        seriesSequence: String? = null,
        publishYear: Int? = 1925,
        publisher: String? = null,
        language: String? = null,
        isbn: String? = null,
        asin: String? = null,
        abridged: Boolean = false,
        rating: Double? = 4.5,
    ): BookDetail {
        val seriesList =
            if (seriesId != null && seriesName != null) {
                listOf(BookSeries(seriesId = seriesId, seriesName = seriesName, sequence = seriesSequence))
            } else {
                emptyList()
            }
        val author = contributor(id = "author-$id", name = authorName, roles = listOf("Author"))
        val narrator = contributor(id = "narrator-$id", name = narratorName, roles = listOf("Narrator"))
        return BookDetail(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            subtitle = subtitle,
            authors = listOf(author),
            narrators = listOf(narrator),
            allContributors = allContributors ?: listOf(author, narrator),
            duration = duration,
            coverPath = coverPath,
            addedAt = Timestamp(1704067200000L),
            updatedAt = Timestamp(1704067200000L),
            description = description,
            genres = genres,
            tags = tags,
            series = seriesList,
            publishYear = publishYear,
            publisher = publisher,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            rating = rating,
        )
    }

    /**
     * Creates a sample [BookListItem] with sensible defaults.
     *
     * Mirrors [book] and [bookDetail] so test sites can use the appropriate type
     * for list/shelf surfaces without carrying detail-only fields (genres, tags,
     * allContributors).
     */
    fun bookListItem(
        id: String = "book-1",
        title: String = "The Great Gatsby",
        subtitle: String? = null,
        authorName: String = "F. Scott Fitzgerald",
        narratorName: String = "Jake Gyllenhaal",
        duration: Long = 5_400_000L,
        coverPath: String? = "/covers/gatsby.jpg",
        coverHash: String? = null,
        description: String? = "A story of decadence and excess in the Jazz Age.",
        seriesId: String? = null,
        seriesName: String? = null,
        seriesSequence: String? = null,
        publishYear: Int? = 1925,
        publisher: String? = null,
        language: String? = null,
        isbn: String? = null,
        asin: String? = null,
        abridged: Boolean = false,
        rating: Double? = 4.5,
    ): BookListItem {
        val seriesList =
            if (seriesId != null && seriesName != null) {
                listOf(BookSeries(seriesId = seriesId, seriesName = seriesName, sequence = seriesSequence))
            } else {
                emptyList()
            }
        val author = contributor(id = "author-$id", name = authorName, roles = listOf("Author"))
        val narrator = contributor(id = "narrator-$id", name = narratorName, roles = listOf("Narrator"))
        return BookListItem(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            subtitle = subtitle,
            authors = listOf(author),
            narrators = listOf(narrator),
            duration = duration,
            coverPath = coverPath,
            coverHash = coverHash,
            addedAt = Timestamp(1704067200000L),
            updatedAt = Timestamp(1704067200000L),
            description = description,
            series = seriesList,
            publishYear = publishYear,
            publisher = publisher,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            rating = rating,
        )
    }

    /**
     * Creates a sample BookContributor.
     */
    fun contributor(
        id: String = "contributor-1",
        name: String = "John Author",
        roles: List<String> = listOf("Author"),
    ): BookContributor =
        BookContributor(
            id = id,
            name = name,
            roles = roles,
        )

    /**
     * Creates a sample Chapter.
     */
    fun chapter(
        id: String = "chapter-1",
        title: String = "Chapter 1",
        duration: Long = 1_800_000L, // 30 min
        startTime: Long = 0L,
    ): Chapter =
        Chapter(
            id = id,
            title = title,
            duration = duration,
            startTime = startTime,
        )

    /**
     * Creates a sample Genre.
     */
    fun genre(
        id: String = "genre-1",
        name: String = "Fiction",
        slug: String = "fiction",
        path: String = "/fiction",
        bookCount: Int = 100,
    ): Genre =
        Genre(
            id = id,
            name = name,
            slug = slug,
            path = path,
            bookCount = bookCount,
        )

    /**
     * Creates a sample Tag.
     */
    fun tag(
        id: String = "tag-1",
        slug: String = "favorites",
        name: String = slug.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } },
    ): Tag =
        Tag(
            id = id,
            name = name,
            slug = slug,
        )

    /**
     * Creates a sample Mood.
     */
    fun mood(
        id: String = "mood-1",
        slug: String = "feel-good",
        name: String = slug.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } },
    ): Mood =
        Mood(
            id = id,
            name = name,
            slug = slug,
        )

    /**
     * Creates a list of sample chapters for a book.
     */
    fun chapters(
        count: Int = 10,
        chapterDuration: Long = 1_800_000L,
    ): List<Chapter> =
        (1..count).map { index ->
            chapter(
                id = "chapter-$index",
                title = "Chapter $index",
                duration = chapterDuration,
                startTime = (index - 1) * chapterDuration,
            )
        }

    /**
     * Creates a sample [BookDocument] with sensible defaults.
     *
     * @param id Server document UUID.
     * @param index 0-based position in the document list.
     * @param filename Book-root-relative path.
     * @param format File extension in lowercase (e.g. `"pdf"`).
     * @param size File size in bytes.
     * @param hash SHA-256 hex digest.
     */
    fun bookDocument(
        id: String = "doc-1",
        index: Int = 0,
        filename: String = "extras/map.pdf",
        format: String = "pdf",
        size: Long = 1_024_000L,
        hash: String = "abc123",
    ): BookDocument =
        BookDocument(
            id = id,
            index = index,
            filename = filename,
            format = format,
            size = size,
            hash = hash,
        )

    /**
     * Creates a sample [BookDetail] with full series information.
     */
    fun bookInSeries(
        id: String = "book-1",
        title: String = "The Fellowship of the Ring",
        seriesId: String = "series-1",
        seriesName: String = "The Lord of the Rings",
        seriesSequence: String = "1",
    ): BookDetail =
        bookDetail(
            id = id,
            title = title,
            seriesId = seriesId,
            seriesName = seriesName,
            seriesSequence = seriesSequence,
        )
}
