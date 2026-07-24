package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.EditableContributor
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Regression test for the per-book alias (`creditedAs`) round-trip through book editing.
 *
 * An author credited under an alias (e.g. the canonical "Stephen King" credited as
 * "Richard Bachman" on a specific book) stores that alias in `credited_as` on the
 * book↔contributor join. Editing the book's contributors and saving used to revert the
 * alias back to the canonical name because the client UPDATE path dropped it: the
 * load → edit → save round-trip never carried `creditedAs` from the loaded book into the
 * [BookContributorInput] sent to the server.
 *
 * This test drives the real [LoadBookForEditUseCase] then [UpdateBookUseCase] and asserts
 * the unedited aliased contributor is saved back with its alias preserved, not nulled.
 */
class ContributorCreditedAsRoundTripTest :
    FunSpec({

        test("loading then saving preserves an unedited contributor's per-book alias (creditedAs)") {
            runTest {
                // Given — a book whose author is credited under an alias. The display name already
                // resolves to the alias; creditedAs holds the raw per-book alias the server stored.
                val aliasedAuthor =
                    BookContributor(
                        id = "c1",
                        name = "Richard Bachman",
                        creditedAs = "Richard Bachman",
                        roles = listOf("Author"),
                    )
                val book = TestData.bookDetail(id = "book-1", allContributors = listOf(aliasedAuthor))

                val bookRepository: BookRepository = mock()
                val genreRepository: GenreRepository = mock()
                val tagRepository: TagRepository = mock()
                val moodRepository: MoodRepository = mock()
                everySuspend { bookRepository.getBookDetail("book-1") } returns book
                everySuspend { genreRepository.getAll() } returns emptyList()
                everySuspend { genreRepository.getGenresForBook(any()) } returns emptyList()
                every { tagRepository.observeAllTags() } returns flowOf(emptyList())
                every { tagRepository.observeTagsForBook(any()) } returns flowOf(emptyList())
                every { moodRepository.observeAllMoods() } returns flowOf(emptyList())
                every { moodRepository.observeMoodsForBook(any()) } returns flowOf(emptyList())

                val loadUseCase =
                    LoadBookForEditUseCase(
                        bookRepository = bookRepository,
                        genreRepository = genreRepository,
                        tagRepository = tagRepository,
                        moodRepository = moodRepository,
                    )

                // When — load the book for editing.
                val loaded = loadUseCase("book-1").shouldBeInstanceOf<AppResult.Success<*>>()
                val editData = loaded.data as com.calypsan.listenup.client.domain.model.BookEditData

                // The loaded editable contributor must carry the alias forward.
                val loadedAuthor = editData.contributors.single { it.id == "c1" }
                loadedAuthor.creditedAs shouldBe "Richard Bachman"

                // Capture the wire inputs the update path produces.
                var capturedInputs: List<BookContributorInput>? = null
                val bookEditRepository: BookEditRepository = mock()
                val imageRepository: ImageRepository = mock()
                val imageStagingRepository: ImageStagingRepository = mock()
                everySuspend { bookEditRepository.updateBook(any(), any()) } returns AppResult.Success(Unit)
                everySuspend { bookEditRepository.setBookContributors(any(), any()) } calls
                    { (_: BookId, inputs: List<BookContributorInput>) ->
                        capturedInputs = inputs
                        AppResult.Success(Unit)
                    }

                val updateUseCase =
                    UpdateBookUseCase(
                        bookEditRepository = bookEditRepository,
                        tagRepository = tagRepository,
                        moodRepository = moodRepository,
                        imageRepository = imageRepository,
                        imageStagingRepository = imageStagingRepository,
                    )

                // The aliased author is left UNCHANGED; the user merely adds a narrator, which forces
                // the full setBookContributors rewrite the bug lives in.
                val addedNarrator =
                    EditableContributor(
                        id = "c2",
                        name = "Frank Muller",
                        roles = setOf(ContributorRole.NARRATOR),
                    )
                val original = editData
                val current =
                    BookUpdateRequest(
                        bookId = editData.bookId,
                        metadata = editData.metadata,
                        contributors = editData.contributors + addedNarrator,
                        series = editData.series,
                        genres = editData.genres,
                        tags = editData.tags,
                        moods = editData.moods,
                        pendingCover = null,
                    )

                // When — save.
                updateUseCase(current, original).shouldBeInstanceOf<AppResult.Success<*>>()

                // Then — the unedited aliased author's per-book alias survives the round-trip.
                val inputs = capturedInputs.shouldNotBeNull()
                val authorInput = inputs.single { it.id?.value == "c1" }
                authorInput.creditedAs shouldBe "Richard Bachman"
            }
        }
    })
