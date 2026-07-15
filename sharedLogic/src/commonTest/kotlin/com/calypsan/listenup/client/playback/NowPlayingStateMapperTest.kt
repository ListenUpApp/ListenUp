package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class NowPlayingStateMapperTest :
    FunSpec({

        val emptyDynamics =
            PlaybackDynamics(
                isPlaying = false,
                isBuffering = false,
                playbackSpeed = 1.0f,
            )

        val emptyMetadata =
            SurfaceMetadata(
                currentChapter = null,
                error = null,
                defaultPlaybackSpeed = 1.0f,
            )

        fun sampleBook(
            id: String = "book-1",
            title: String = "Sample Book",
            duration: Long = 100_000L,
        ): BookListItem =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                authors = listOf(BookContributor(id = "author-1", name = "Test Author", roles = listOf("Author"))),
                narrators = listOf(BookContributor(id = "narrator-1", name = "Test Narrator", roles = listOf("Narrator"))),
                duration = duration,
                coverPath = "/covers/sample.jpg",
                addedAt = Timestamp(epochMillis = 1_704_067_200_000L),
                updatedAt = Timestamp(epochMillis = 1_704_067_200_000L),
            )

        test("mapToNowPlayingState returns Idle when book is null and no error") {
            val result = mapToNowPlayingState(book = null, dynamics = emptyDynamics, metadata = emptyMetadata)
            result shouldBe NowPlayingState.Idle
        }

        test("mapToNowPlayingState returns Error with null bookId when book is null and error present") {
            val metadata =
                emptyMetadata.copy(
                    error =
                        PlaybackManager.PlaybackErrorUiState(
                            message = "Network failure",
                            isRecoverable = true,
                            timestampMs = 1_000L,
                        ),
                )
            val result = mapToNowPlayingState(book = null, dynamics = emptyDynamics, metadata = metadata)
            val error = result.shouldBeInstanceOf<NowPlayingState.Error>()
            error.bookId shouldBe null
            error.title shouldBe null
            error.message shouldBe "Network failure"
            error.isRecoverable shouldBe true
        }

        test("mapToNowPlayingState returns Error with bookId when both book and error present") {
            val book = sampleBook()
            val metadata =
                emptyMetadata.copy(
                    error =
                        PlaybackManager.PlaybackErrorUiState(
                            message = "Codec error",
                            isRecoverable = false,
                            timestampMs = 1_000L,
                        ),
                )
            val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = metadata)
            val error = result.shouldBeInstanceOf<NowPlayingState.Error>()
            error.bookId shouldBe "book-1"
            error.title shouldBe "Sample Book"
            error.message shouldBe "Codec error"
            error.isRecoverable shouldBe false
        }

        test("mapToNowPlayingState returns Active for normal playback") {
            val book = sampleBook(duration = 100_000L)
            val dynamics =
                emptyDynamics.copy(
                    isPlaying = true,
                    playbackSpeed = 1.5f,
                )
            val result = mapToNowPlayingState(book = book, dynamics = dynamics, metadata = emptyMetadata)
            val active = result.shouldBeInstanceOf<NowPlayingState.Active>()
            active.bookId shouldBe "book-1"
            active.isPlaying shouldBe true
            active.playbackSpeed shouldBe 1.5f
        }

        test("mapToNowPlayingState Active chapterLabel is empty when totalChapters is 0") {
            val book = sampleBook()
            val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = emptyMetadata)
            val active = result.shouldBeInstanceOf<NowPlayingState.Active>()
            active.totalChapters shouldBe 0
            active.chapterLabel shouldBe ""
        }

        test("mapToNowPlayingState Active totalChapters pulled from current chapter") {
            val book = sampleBook()
            val chapter =
                PlaybackManager.ChapterInfo(
                    index = 1,
                    title = "Chapter 2",
                    startMs = 60_000L,
                    endMs = 90_000L,
                    remainingMs = 30_000L,
                    totalChapters = 3,
                    isGenericTitle = false,
                )
            val metadata = emptyMetadata.copy(currentChapter = chapter)
            val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = metadata)
            val active = result.shouldBeInstanceOf<NowPlayingState.Active>()
            active.totalChapters shouldBe 3 // pulled from chapter.totalChapters
        }

        test("mapToPlaybackProgress computes book and chapter progress") {
            val chapter =
                PlaybackManager.ChapterInfo(
                    index = 1,
                    title = "Two",
                    startMs = 40_000L,
                    endMs = 100_000L,
                    remainingMs = 50_000L,
                    totalChapters = 3,
                    isGenericTitle = false,
                )
            val p = mapToPlaybackProgress(currentPositionMs = 50_000L, totalDurationMs = 200_000L, chapter = chapter)
            p.bookPositionMs shouldBe 50_000L
            p.bookDurationMs shouldBe 200_000L
            p.bookProgress shouldBe 0.25f
            p.chapterPositionMs shouldBe 10_000L
            p.chapterDurationMs shouldBe 60_000L
            p.chapterProgress shouldBe (10_000f / 60_000f)
        }

        test("mapToPlaybackProgress clamps progress and floors chapter position at zero") {
            val p = mapToPlaybackProgress(currentPositionMs = 300_000L, totalDurationMs = 200_000L, chapter = null)
            p.bookProgress shouldBe 1f
            p.chapterPositionMs shouldBe 0L
            p.chapterProgress shouldBe 0f
        }

        test("mapToPlaybackProgress with zero duration yields zero progress") {
            val p = mapToPlaybackProgress(currentPositionMs = 0L, totalDurationMs = 0L, chapter = null)
            p shouldBe PlaybackProgress.Zero
        }

        test("mapToNowPlayingState Active chapters populated in order with titles and durations") {
            val book = sampleBook(duration = 300_000L)
            val domainChapters =
                listOf(
                    Chapter(id = "c0", title = "Prologue", duration = 60_000L, startTime = 0L),
                    Chapter(id = "c1", title = "Chapter One", duration = 120_000L, startTime = 60_000L),
                    Chapter(id = "c2", title = "Chapter Two", duration = 120_000L, startTime = 180_000L),
                )
            val metadata = emptyMetadata.copy(chapters = domainChapters)
            val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = metadata)
            val active = result.shouldBeInstanceOf<NowPlayingState.Active>()
            active.chapters.size shouldBe 3
            active.chapters[0] shouldBe NowPlayingChapter(index = 0, title = "Prologue", durationMs = 60_000L)
            active.chapters[1] shouldBe NowPlayingChapter(index = 1, title = "Chapter One", durationMs = 120_000L)
            active.chapters[2] shouldBe NowPlayingChapter(index = 2, title = "Chapter Two", durationMs = 120_000L)
        }

        test("mapToNowPlayingState Active chapters is empty when no chapters provided") {
            val book = sampleBook()
            val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = emptyMetadata)
            val active = result.shouldBeInstanceOf<NowPlayingState.Active>()
            active.chapters shouldBe emptyList()
        }
    })
