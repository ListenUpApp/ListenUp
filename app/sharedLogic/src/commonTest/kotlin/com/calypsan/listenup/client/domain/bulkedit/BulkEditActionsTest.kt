package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The one pure function bulk editing is built on.
 *
 * Every property that keeps a bulk edit safe is decided here, not in the form: a field nobody
 * touched produces nothing, an instruction a book already satisfies produces nothing, and adding
 * never removes. Testing it at this level means the guarantees hold no matter what the UI does.
 */
class BulkEditActionsTest :
    FunSpec({

        fun book(
            publisher: String? = null,
            publishYear: Int? = null,
            language: String? = null,
            genres: List<Genre> = emptyList(),
            tags: List<Tag> = emptyList(),
            moods: List<Mood> = emptyList(),
            series: List<BookSeries> = emptyList(),
            contributors: List<BookContributor> = emptyList(),
        ) = BookDetail(
            id = BookId("b1"),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "The Way of Kings",
            authors = emptyList(),
            narrators = emptyList(),
            duration = 1_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
            publisher = publisher,
            publishYear = publishYear,
            language = language,
            genres = genres,
            tags = tags,
            moods = moods,
            series = series,
            allContributors = contributors,
        )

        fun genre(id: String) = Genre(id = id, name = id, slug = id, path = "/$id")

        test("no instructions produce no actions") {
            emptyList<BulkEdit>().actionsFor(book()).shouldBeEmpty()
        }

        test("a field the book already satisfies produces nothing") {
            // The dedupe that stops forty selected books becoming forty pointless outbox rows and
            // forty sync frames to every other device.
            val edits = listOf(BulkEdit.SetPublisher("Tor"))

            edits.actionsFor(book(publisher = "Tor")).shouldBeEmpty()
        }

        test("scalars merge into a single Update rather than three") {
            val edits =
                listOf(
                    BulkEdit.SetPublisher("Tor"),
                    BulkEdit.SetPublishYear(2024),
                    BulkEdit.SetLanguage("en"),
                )

            val actions = edits.actionsFor(book())

            actions.size shouldBe 1
            val patch = actions.single().shouldBeInstanceOf<BulkAction.Mutate>().mutation
            val update = patch.shouldBeInstanceOf<BookMutation.Update>().patch
            update.publisher shouldBe "Tor"
            update.publishYear shouldBe 2024
            update.language shouldBe "en"
        }

        test("only the scalars that actually differ reach the patch") {
            val edits = listOf(BulkEdit.SetPublisher("Tor"), BulkEdit.SetPublishYear(2024))

            val actions = edits.actionsFor(book(publisher = "Tor"))

            val update =
                actions
                    .single()
                    .shouldBeInstanceOf<BulkAction.Mutate>()
                    .mutation
                    .shouldBeInstanceOf<BookMutation.Update>()
                    .patch
            withClue("a value the book already has must not be restated") {
                update.publisher shouldBe null
            }
            update.publishYear shouldBe 2024
        }

        test("adding genres keeps the ones already there") {
            val edits = listOf(BulkEdit.AddGenres(listOf(BookGenreInput(genreId = GenreId("grimdark")))))

            val actions = edits.actionsFor(book(genres = listOf(genre("epic-fantasy"))))

            val genres =
                actions
                    .single()
                    .shouldBeInstanceOf<BulkAction.Mutate>()
                    .mutation
                    .shouldBeInstanceOf<BookMutation.SetGenres>()
                    .genres
                    .map { it.genreId.value }
            withClue("union, not replace — the book's own classification must survive") {
                genres shouldContainExactly listOf("epic-fantasy", "grimdark")
            }
        }

        test("a genre the book already has produces no action") {
            val edits = listOf(BulkEdit.AddGenres(listOf(BookGenreInput(genreId = GenreId("epic-fantasy")))))

            edits.actionsFor(book(genres = listOf(genre("epic-fantasy")))).shouldBeEmpty()
        }

        test("adding a series unions, and leaves sequence unset") {
            val edits = listOf(BulkEdit.AddToSeries(BookSeriesInput(id = null, name = "Stormlight")))

            val actions =
                edits.actionsFor(
                    book(series = listOf(BookSeries(seriesId = "s1", seriesName = "Cosmere", sequence = 3.0))),
                )

            val series =
                actions
                    .single()
                    .shouldBeInstanceOf<BulkAction.Mutate>()
                    .mutation
                    .shouldBeInstanceOf<BookMutation.SetSeries>()
                    .series
            series.map { it.name } shouldContainExactly listOf("Cosmere", "Stormlight")
            withClue("the existing membership keeps its number") {
                series.first { it.name == "Cosmere" }.position shouldBe 3.0
            }
            withClue("one value across forty books would make them all Book 1") {
                series.first { it.name == "Stormlight" }.position shouldBe null
            }
        }

        test("adding a series the book is already in produces no action") {
            val edits = listOf(BulkEdit.AddToSeries(BookSeriesInput(id = null, name = "Cosmere")))

            val existing = listOf(BookSeries(seriesId = "s1", seriesName = "Cosmere", sequence = 3.0))

            edits.actionsFor(book(series = existing)).shouldBeEmpty()
        }

        test("adding contributors keeps existing credits and renumbers positions") {
            val edits =
                listOf(
                    BulkEdit.AddContributors(
                        listOf(BookContributorInput(id = null, name = "Kate Reading", role = "narrator", position = 0)),
                    ),
                )

            val existing =
                listOf(BookContributor(id = "c1", name = "Brandon Sanderson", roles = listOf("author")))

            val contributors =
                edits
                    .actionsFor(book(contributors = existing))
                    .single()
                    .shouldBeInstanceOf<BulkAction.Mutate>()
                    .mutation
                    .shouldBeInstanceOf<BookMutation.SetContributors>()
                    .contributors

            contributors.map { it.name } shouldContainExactly listOf("Brandon Sanderson", "Kate Reading")
            withClue("positions must be contiguous from zero or the server's ordering is wrong") {
                contributors.map { it.position } shouldContainExactly listOf(0, 1)
            }
        }

        test("a contributor already credited in that role produces no action") {
            val edits =
                listOf(
                    BulkEdit.AddContributors(
                        listOf(
                            BookContributorInput(
                                id = null,
                                name = "Brandon Sanderson",
                                role = "author",
                                position = 0,
                            ),
                        ),
                    ),
                )

            val existing =
                listOf(BookContributor(id = "c1", name = "Brandon Sanderson", roles = listOf("author")))

            edits.actionsFor(book(contributors = existing)).shouldBeEmpty()
        }

        test("tags and moods become their own actions, one per slug") {
            val edits =
                listOf(
                    BulkEdit.AddTags(listOf("found-family", "grimdark")),
                    BulkEdit.AddMoods(listOf("bleak")),
                )

            val actions = edits.actionsFor(book())

            actions.filterIsInstance<BulkAction.AddTag>().map { it.slug } shouldContainExactly
                listOf("found-family", "grimdark")
            actions.filterIsInstance<BulkAction.AddMood>().map { it.slug } shouldContainExactly listOf("bleak")
        }

        test("a tag the book already carries produces no action") {
            val edits = listOf(BulkEdit.AddTags(listOf("found-family")))

            val existing = listOf(Tag(id = "t1", name = "Found Family", slug = "found-family"))

            edits.actionsFor(book(tags = existing)).shouldBeEmpty()
        }
    })
