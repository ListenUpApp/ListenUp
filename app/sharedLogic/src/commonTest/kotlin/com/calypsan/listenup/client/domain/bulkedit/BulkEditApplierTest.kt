package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The one place that knows which repository serves which action.
 *
 * Keeping that knowledge here rather than in the ViewModel is what lets the pure planning function
 * stay pure, and what makes a new field a `when` branch rather than a new dependency on the screen.
 */
class BulkEditApplierTest :
    FunSpec({

        fun rig(): Triple<BulkEditApplier, BookEditRepository, TagRepository> {
            val books = mock<BookEditRepository>(MockMode.autoUnit)
            everySuspend { books.updateBook(any(), any()) } returns AppResult.Success(Unit)

            val tags = mock<TagRepository>(MockMode.autoUnit)
            everySuspend { tags.addTagToBook(any(), any()) } returns
                AppResult.Success(Tag(id = "t1", name = "Found Family", slug = "found-family"))

            val moods = mock<MoodRepository>(MockMode.autoUnit)
            everySuspend { moods.addMoodToBook(any(), any()) } returns
                AppResult.Success(Mood(id = "m1", name = "Bleak", slug = "bleak"))

            return Triple(BulkEditApplier(books, tags, moods), books, tags)
        }

        test("a Mutate action reaches the book edit repository") {
            val (applier, books, _) = rig()
            runTest {
                val action = BulkAction.Mutate(BookMutation.Update(BookUpdate(publisher = "Tor")))

                applier.apply(BookId("b1"), listOf(action)) shouldBe AppResult.Success(Unit)

                verifySuspend(exactly(1)) { books.updateBook(BookId("b1"), BookUpdate(publisher = "Tor")) }
            }
        }

        test("a tag action reaches the tag repository by slug") {
            val (applier, _, tags) = rig()
            runTest {
                applier.apply(BookId("b1"), listOf(BulkAction.AddTag("found-family")))

                verifySuspend(exactly(1)) { tags.addTagToBook("b1", "found-family") }
            }
        }

        test("applying nothing touches no repository") {
            // A book the instructions already satisfy must cost nothing at all — no call, no outbox
            // row, no sync frame.
            val (applier, books, tags) = rig()
            runTest {
                applier.apply(BookId("b1"), emptyList()) shouldBe AppResult.Success(Unit)

                verifySuspend(exactly(0)) { books.updateBook(any(), any()) }
                verifySuspend(exactly(0)) { tags.addTagToBook(any(), any()) }
            }
        }

        test("the first failure is returned and stops the rest") {
            val books = mock<BookEditRepository>(MockMode.autoUnit)
            val failure = AppResult.Failure(BookError.InvalidInput())
            everySuspend { books.updateBook(any(), any()) } returns failure

            val tags = mock<TagRepository>(MockMode.autoUnit)
            val moods = mock<MoodRepository>(MockMode.autoUnit)
            val applier = BulkEditApplier(books, tags, moods)

            runTest {
                val result =
                    applier.apply(
                        BookId("b1"),
                        listOf(
                            BulkAction.Mutate(BookMutation.Update(BookUpdate(publisher = "Tor"))),
                            BulkAction.AddTag("found-family"),
                        ),
                    )

                result shouldBe failure
                verifySuspend(exactly(0)) { tags.addTagToBook(any(), any()) }
            }
        }
    })
