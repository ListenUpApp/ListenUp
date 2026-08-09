package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp

/**
 * A loaded book, for specs about layout, URL contracts and selection.
 *
 * The sample lives here rather than in the page it drives: the page renders whatever the shared
 * ViewModel gives it, and a fixture in the source set would be a fixture users could reach. That
 * the real ViewModel produces this shape is proven separately by `ClientGraphProbeTest`.
 */
internal fun readyBook(chapters: List<ChapterUiModel> = sampleChapters()): BookDetailUiState.Ready =
    BookDetailUiState.Ready(
        book =
            BookDetail(
                id = BookId("42"),
                libraryId = LibraryId("library-1"),
                folderId = FolderId("folder-1"),
                title = "The Institute",
                authors = listOf(BookContributor(id = "c1", name = "Stephen King")),
                narrators = listOf(BookContributor(id = "c2", name = "Santino Fontana")),
                duration = TOTAL_SECONDS * MILLIS_PER_SECOND,
                coverPath = null,
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
                description = "He wakes up at The Institute, in a room that looks just like his own.",
                publishYear = 2019,
                publisher = "Scribner",
                genres = listOf(Genre(id = "g1", name = "Horror", slug = "horror", path = "/horror")),
            ),
        descriptionText = "He wakes up at The Institute, in a room that looks just like his own.",
        narrators = "Santino Fontana",
        year = 2019,
        chapters = chapters,
        genres = listOf(Genre(id = "g1", name = "Horror", slug = "horror", path = "/horror")),
    )

/**
 * 33 chapters whose durations vary but always sum to the book's runtime — the two panes
 * describing one book must not disagree, even in a fixture.
 */
internal fun sampleChapters(): List<ChapterUiModel> {
    val durations = IntArray(CHAPTER_COUNT) { index -> BASE_SECONDS + index * VARIATION_STEP % VARIATION_RANGE }
    durations[CHAPTER_COUNT - 1] += TOTAL_SECONDS.toInt() - durations.sum()
    var start = 0L
    return durations.mapIndexed { index, duration ->
        val chapter =
            ChapterUiModel(
                id = "ch-${index + 1}",
                title = "Chapter ${index + 1}",
                duration = formatClock(duration),
                imageUrl = null,
                startMs = start * MILLIS_PER_SECOND,
                durationMs = duration * MILLIS_PER_SECOND.toLong(),
            )
        start += duration
        chapter
    }
}

/** 9:14:06 — the runtime the Details panel reports. */
private const val TOTAL_SECONDS = 9L * 3600 + 14 * 60 + 6

private const val MILLIS_PER_SECOND = 1000L

private const val CHAPTER_COUNT = 33

private const val BASE_SECONDS = 700

private const val VARIATION_STEP = 137

private const val VARIATION_RANGE = 600
