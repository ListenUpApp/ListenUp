package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.SeriesId
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips every book patch DTO through [contractJson]. Catches field-name drift,
 * `init`-block validation regressions, and default-value handling before any pipeline code runs.
 */
class BookUpdateContractTest :
    FunSpec({

        // ── BookUpdate ────────────────────────────────────────────────────────

        test("should round-trip BookUpdate with all fields populated") {
            val original =
                BookUpdate(
                    title = "New Title",
                    sortTitle = "Title, New",
                    subtitle = "A Subtitle",
                    description = "Long description text",
                    publisher = "Tor",
                    publishYear = 2026,
                    language = "en",
                    isbn = "978-0-7653-2635-5",
                    asin = "B0XXXXXXXX",
                    abridged = false,
                    addedAt = 1_700_000_000_000L,
                )
            roundTrip<BookUpdate>(original) shouldBe original
        }

        test("should preserve null when BookUpdate has no fields populated") {
            val original = BookUpdate()
            val decoded = roundTrip<BookUpdate>(original)
            decoded shouldBe original
            decoded.title shouldBe null
            decoded.publishYear shouldBe null
        }

        test("should throw when BookUpdate title is blank") {
            shouldThrow<IllegalArgumentException> {
                BookUpdate(title = "")
            }
        }

        test("should throw when BookUpdate publishYear is out of range") {
            shouldThrow<IllegalArgumentException> {
                BookUpdate(publishYear = 99_999)
            }
        }

        test("should throw when BookUpdate addedAt is not positive") {
            shouldThrow<IllegalArgumentException> {
                BookUpdate(addedAt = 0L)
            }
        }

        // ── BookContributorInput ──────────────────────────────────────────────

        test("should round-trip BookContributorInput with all fields populated") {
            val original =
                BookContributorInput(
                    id = ContributorId("contributor-1"),
                    name = "Stephen King",
                    role = "author",
                    creditedAs = "S. King",
                    position = 0,
                )
            roundTrip<BookContributorInput>(original) shouldBe original
        }

        test("should round-trip BookContributorInput when id is null") {
            val original =
                BookContributorInput(
                    id = null,
                    name = "Stephen King",
                    role = "author",
                    creditedAs = null,
                    position = 0,
                )
            roundTrip<BookContributorInput>(original) shouldBe original
        }

        test("should throw when BookContributorInput name is blank") {
            shouldThrow<IllegalArgumentException> {
                BookContributorInput(name = "", role = "author", position = 0)
            }
        }

        test("should throw when BookContributorInput role is blank") {
            shouldThrow<IllegalArgumentException> {
                BookContributorInput(name = "Someone", role = "", position = 0)
            }
        }

        // ── BookSeriesInput ───────────────────────────────────────────────────

        test("should round-trip BookSeriesInput with all fields populated") {
            val original =
                BookSeriesInput(
                    id = SeriesId("series-1"),
                    name = "The Dark Tower",
                    position = 1.0,
                    isPrimary = true,
                )
            roundTrip<BookSeriesInput>(original) shouldBe original
        }

        test("should round-trip BookSeriesInput when id and position are null") {
            val original =
                BookSeriesInput(
                    id = null,
                    name = "The Dark Tower",
                    position = null,
                    isPrimary = false,
                )
            roundTrip<BookSeriesInput>(original) shouldBe original
        }

        test("should throw when BookSeriesInput name is blank") {
            shouldThrow<IllegalArgumentException> {
                BookSeriesInput(name = "")
            }
        }

        // ── ContributorUpdate ─────────────────────────────────────────────────

        test("should round-trip ContributorUpdate with all fields populated") {
            val original =
                ContributorUpdate(
                    name = "Stephen King",
                    sortName = "King, Stephen",
                    asin = "B000APXXXX",
                    description = "Bio",
                    imagePath = "/covers/king.jpg",
                    birthDate = "1947-09-21",
                    deathDate = null,
                    website = "https://stephenking.com",
                )
            roundTrip<ContributorUpdate>(original) shouldBe original
        }

        test("should preserve null when ContributorUpdate has no fields populated") {
            val original = ContributorUpdate()
            roundTrip<ContributorUpdate>(original) shouldBe original
        }

        test("should throw when ContributorUpdate name is blank") {
            shouldThrow<IllegalArgumentException> {
                ContributorUpdate(name = "")
            }
        }

        // ── SeriesUpdate ──────────────────────────────────────────────────────

        test("should round-trip SeriesUpdate with all fields populated") {
            val original =
                SeriesUpdate(
                    name = "The Dark Tower",
                    sortName = "Dark Tower, The",
                    description = "A series",
                    coverPath = "/covers/dt.jpg",
                    asin = "B0XXX",
                )
            roundTrip<SeriesUpdate>(original) shouldBe original
        }

        test("should preserve null when SeriesUpdate has no fields populated") {
            val original = SeriesUpdate()
            roundTrip<SeriesUpdate>(original) shouldBe original
        }

        test("should throw when SeriesUpdate name is blank") {
            shouldThrow<IllegalArgumentException> {
                SeriesUpdate(name = "")
            }
        }

        // ── BookMutation (unified outbox payload) ─────────────────────────────

        test("should round-trip every BookMutation variant through the sealed serializer") {
            roundTrip<BookMutation>(BookMutation.Update(BookUpdate(title = "New"))) shouldBe
                BookMutation.Update(BookUpdate(title = "New"))

            val contributors =
                BookMutation.SetContributors(
                    listOf(BookContributorInput(id = ContributorId("c1"), name = "King", role = "author", position = 0)),
                )
            roundTrip<BookMutation>(contributors) shouldBe contributors

            val series = BookMutation.SetSeries(listOf(BookSeriesInput(id = SeriesId("s1"), name = "Tower", position = 1.0)))
            roundTrip<BookMutation>(series) shouldBe series

            val genres = BookMutation.SetGenres(listOf(BookGenreInput(GenreId("g1"))))
            roundTrip<BookMutation>(genres) shouldBe genres

            val chapters = BookMutation.SetChapters(listOf(ChapterInput(id = "ch1", title = "One", startTime = 0, duration = 1)))
            roundTrip<BookMutation>(chapters) shouldBe chapters

            val collections = BookMutation.SetCollections(listOf("col1", "col2"))
            roundTrip<BookMutation>(collections) shouldBe collections

            roundTrip<BookMutation>(BookMutation.DeleteCover).shouldBeInstanceOf<BookMutation.DeleteCover>()
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
