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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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

        /** The applier and the three repositories it dispatches to, each stubbed to succeed. */
        data class Rig(
            val applier: BulkEditApplier,
            val books: BookEditRepository,
            val tags: TagRepository,
            val moods: MoodRepository,
        )

        fun rig(): Rig {
            val books = mock<BookEditRepository>(MockMode.autoUnit)
            everySuspend { books.updateBook(any(), any()) } returns AppResult.Success(Unit)

            val tags = mock<TagRepository>(MockMode.autoUnit)
            everySuspend { tags.addTagToBook(any(), any()) } returns
                AppResult.Success(Tag(id = "t1", name = "Found Family", slug = "found-family"))

            val moods = mock<MoodRepository>(MockMode.autoUnit)
            everySuspend { moods.addMoodToBook(any(), any()) } returns
                AppResult.Success(Mood(id = "m1", name = "Bleak", slug = "bleak"))

            return Rig(BulkEditApplier(books, tags, moods), books, tags, moods)
        }

        test("a Mutate action reaches the book edit repository") {
            val rig = rig()
            runTest {
                val action = BulkAction.Mutate(BookMutation.Update(BookUpdate(publisher = "Tor")))

                rig.applier.apply(BookId("b1"), listOf(action)) shouldBe AppResult.Success(Unit)

                verifySuspend(exactly(1)) { rig.books.updateBook(BookId("b1"), BookUpdate(publisher = "Tor")) }
            }
        }

        test("a tag action reaches the tag repository by display name") {
            val rig = rig()
            runTest {
                rig.applier.apply(BookId("b1"), listOf(BulkAction.AddTag("Found Family")))

                withClue("addTagToBook slugifies; a slug passed here would name the created tag") {
                    verifySuspend(exactly(1)) { rig.tags.addTagToBook("b1", "Found Family") }
                }
            }
        }

        test("a mood action reaches the mood repository by display name") {
            val rig = rig()
            runTest {
                rig.applier.apply(BookId("b1"), listOf(BulkAction.AddMood("Bleak"))) shouldBe
                    AppResult.Success(Unit)

                verifySuspend(exactly(1)) { rig.moods.addMoodToBook("b1", "Bleak") }
            }
        }

        test("a mutation a bulk edit cannot express is refused loudly, not silently ignored") {
            // actionsFor never plans one. If a ninth BookMutation ever reaches here, failing is the
            // only honest answer — quietly doing nothing would report a bulk edit as applied.
            val rig = rig()
            runTest {
                shouldThrow<IllegalStateException> {
                    rig.applier.apply(BookId("b1"), listOf(BulkAction.Mutate(BookMutation.DeleteCover)))
                }
            }
        }

        test("applying nothing touches no repository") {
            // A book the instructions already satisfy must cost nothing at all — no call, no outbox
            // row, no sync frame.
            val rig = rig()
            runTest {
                rig.applier.apply(BookId("b1"), emptyList()) shouldBe AppResult.Success(Unit)

                verifySuspend(exactly(0)) { rig.books.updateBook(any(), any()) }
                verifySuspend(exactly(0)) { rig.tags.addTagToBook(any(), any()) }
                verifySuspend(exactly(0)) { rig.moods.addMoodToBook(any(), any()) }
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
                            BulkAction.AddTag("Found Family"),
                        ),
                    )

                result shouldBe failure
                verifySuspend(exactly(0)) { tags.addTagToBook(any(), any()) }
            }
        }
    })
