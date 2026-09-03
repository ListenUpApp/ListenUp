package com.calypsan.listenup.web.features.seriesdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun seriesDetailPage(
    state: SeriesDetailUiState,
    onOpenLibrary: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onPlayBook: (String) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        SeriesDetailPage(
            state = state,
            onOpenLibrary = onOpenLibrary,
            onOpenBook = onOpenBook,
            onPlayBook = onPlayBook,
        )
    }
    return root
}

/**
 * Series Detail rendered against the shared [SeriesDetailUiState].
 *
 * What these pin: the hero reports the ViewModel's own stats rather than re-deriving them from the
 * rows on screen, the books render in the order the ViewModel sorted them and carry THIS series'
 * position, progress and "finished" come only from what the state actually knows, the resume
 * button says which of the two things it does, and every non-Ready state offers an explanation and
 * a way back.
 */
class SeriesDetailPageTest :
    FunSpec({

        test("the hero renders the series' name and its author") {
            val root = seriesDetailPage(readySeries())

            (root.querySelector(".sd-t") as HTMLElement).textContent shouldBe "The Stormlight Archive"
            (root.querySelector(".sd-by") as HTMLElement).textContent shouldBe "Brandon Sanderson"
        }

        test("a multi-author series names every author, not just the first book's") {
            val root =
                seriesDetailPage(
                    readySeries(
                        seriesAuthors =
                            listOf(
                                BookContributor(id = "c1", name = "Robert Jordan"),
                                BookContributor(id = "c2", name = "Brandon Sanderson"),
                            ),
                    ),
                )

            val line = (root.querySelector(".sd-by") as HTMLElement).textContent.orEmpty()
            line shouldContain "Robert Jordan"
            line shouldContain "Brandon Sanderson"
        }

        test("a series whose books name no author renders no byline at all") {
            val root = seriesDetailPage(readySeries(seriesAuthors = emptyList()))

            root.querySelector(".sd-by") shouldBe null
        }

        // The stat is the ViewModel's `totalDuration`, and the fixture's books deliberately do not
        // sum to it — a hero that added up the rows it was rendering would show a different number.
        test("the audio stat is the state's total, not the sum of the rows on screen") {
            val root = seriesDetailPage(readySeries())

            val stats = root.querySelectorAll(".sd-stat")
            (stats.item(0) as HTMLElement).textContent.orEmpty() shouldContain "2 books"
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "92h"
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "of audio"
        }

        test("the audio stat never says 'listened' — it is series duration, not listening history") {
            val root = seriesDetailPage(readySeries())

            root.textContent.orEmpty() shouldNotContain "listened"
        }

        test("a one-book series reads as '1 book', not '1 books'") {
            val root = seriesDetailPage(readySeries(books = listOf(seriesBook("b1", "Elantris", 1.0))))

            (root.querySelector(".sd-stat") as HTMLElement).textContent.orEmpty() shouldContain "1 book"
        }

        // A statistic about a reader who has done nothing is one the page invented.
        test("a series nobody has finished shows no 'finished' stat") {
            val root = seriesDetailPage(readySeries())

            root.textContent.orEmpty() shouldNotContain "finished"
        }

        test("the finished stat counts what the state marked finished") {
            val root = seriesDetailPage(readySeries(finishedBookIds = setOf(BookId("b1"))))

            root.textContent.orEmpty() shouldContain "1 finished"
        }

        test("books render in the order the state gave them") {
            val root =
                seriesDetailPage(
                    readySeries(
                        books =
                            listOf(
                                seriesBook("b2", "Words of Radiance", 2.0),
                                seriesBook("b1", "The Way of Kings", 1.0),
                            ),
                    ),
                )

            val titles = root.querySelectorAll(".sd-book-t")
            (titles.item(0) as HTMLElement).textContent shouldBe "Words of Radiance"
            (titles.item(1) as HTMLElement).textContent shouldBe "The Way of Kings"
        }

        // A book in two series has two positions. Reading `series.first()` would show the wrong
        // one on whichever of the two pages lost the coin toss.
        test("a book in two series shows its position in THIS one") {
            val root =
                seriesDetailPage(
                    readySeries(
                        books =
                            listOf(
                                seriesBook(
                                    id = "b1",
                                    title = "The Way of Kings",
                                    sequence = 1.0,
                                    extraSeries = listOf(BookSeries(seriesId = "s-cosmere", seriesName = "The Cosmere", sequence = 7.0)),
                                ),
                            ),
                    ),
                )

            (root.querySelector(".sd-seq") as HTMLElement).textContent shouldBe "#1"
        }

        // ⛔ Pins the OUTPUT, not the mechanism. On Kotlin/JS a whole `Double` stringifies as
        // "1", so interpolating the raw `sequence` renders identically here — verified by
        // sabotage: swapping `sequenceLabel` for `sequence` fails nothing in this suite. The
        // "Book 1.0" bug this guards against everywhere else is unreachable in a browser. The
        // call site still reads `sequenceLabel`, because the JVM and Native ones must.
        test("a whole-numbered position reads '#1', never '#1.0'") {
            val root = seriesDetailPage(readySeries(books = listOf(seriesBook("b1", "The Way of Kings", 1.0))))

            (root.querySelector(".sd-seq") as HTMLElement).textContent shouldBe "#1"
        }

        test("an interquel keeps its fractional position") {
            val root = seriesDetailPage(readySeries(books = listOf(seriesBook("b1", "Edgedancer", 2.5))))

            (root.querySelector(".sd-seq") as HTMLElement).textContent shouldBe "#2.5"
        }

        test("an unnumbered book draws no position rather than a placeholder") {
            val root = seriesDetailPage(readySeries(books = listOf(seriesBook("b1", "Arcanum Unbounded", null))))

            root.querySelector(".sd-seq") shouldBe null
        }

        // `bookProgress` carries in-progress books ONLY, so a bar on any other row would be false.
        test("only a book the state has progress for draws a progress bar") {
            val root =
                seriesDetailPage(
                    readySeries(bookProgress = mapOf(BookId("b1") to 0.5f)),
                )

            root.querySelectorAll(".sd-book-progress").length shouldBe 1
        }

        test("a finished book is marked finished") {
            val root = seriesDetailPage(readySeries(finishedBookIds = setOf(BookId("b2"))))

            val marks = root.querySelectorAll(".sd-done")
            marks.length shouldBe 1
            (marks.item(0) as HTMLElement).textContent.orEmpty() shouldContain "Finished"
        }

        test("clicking a book opens that book") {
            val opened = mutableListOf<String>()
            val root = seriesDetailPage(readySeries(), onOpenBook = { opened += it })

            (root.querySelectorAll(".sd-book").item(1) as HTMLElement).click()

            opened shouldBe listOf("b2")
        }

        // Two different things — picking up where you left off, and starting something new. The
        // button has to say which, because the reader is deciding whether to press it.
        test("an in-progress series offers Continue") {
            val root =
                seriesDetailPage(
                    readySeries(bookProgress = mapOf(BookId("b1") to 0.4f), resumeTarget = BookId("b1")),
                )

            (root.querySelector(".sd-actions") as HTMLElement).textContent.orEmpty() shouldContain "Continue"
        }

        test("an untouched series offers Start") {
            val root = seriesDetailPage(readySeries(resumeTarget = BookId("b1")))

            (root.querySelector(".sd-actions") as HTMLElement).textContent.orEmpty() shouldContain "Start"
        }

        // Restarting book one is a decision the reader did not make.
        test("a finished series offers no resume button at all") {
            val root =
                seriesDetailPage(
                    readySeries(
                        finishedBookIds = setOf(BookId("b1"), BookId("b2")),
                        resumeTarget = null,
                    ),
                )

            root.querySelector(".sd-actions") shouldBe null
        }

        test("the resume button plays the state's resume target, not the first book") {
            val played = mutableListOf<String>()
            val root =
                seriesDetailPage(
                    readySeries(bookProgress = mapOf(BookId("b2") to 0.3f), resumeTarget = BookId("b2")),
                    onPlayBook = { played += it },
                )

            (root.querySelector(".sd-actions button") as HTMLElement).click()

            played shouldBe listOf("b2")
        }

        test("a series with no description renders no About panel") {
            val root = seriesDetailPage(readySeries())

            root.textContent.orEmpty() shouldNotContain "About"
        }

        test("a described series renders its description") {
            val root = seriesDetailPage(readySeries(seriesDescription = "Ten orders of Knights Radiant."))

            (root.querySelector(".sd-desc") as HTMLElement).textContent.orEmpty() shouldContain "Knights Radiant"
        }

        test("a series that cannot be shown explains itself and offers a way back") {
            val opened = mutableListOf<Unit>()
            val root =
                seriesDetailPage(
                    SeriesDetailUiState.Error("Series not found"),
                    onOpenLibrary = { opened += Unit },
                )

            root.textContent.orEmpty() shouldContain "Series not found"
            val back = root.querySelector(".empty button") as HTMLElement
            back.click()
            opened.size shouldBe 1
        }

        // A page that cannot show what you asked for must still show the way out of it.
        test("the breadcrumb renders in every state, including the ones with no series") {
            val root = seriesDetailPage(SeriesDetailUiState.Loading)

            root.querySelector(".crumb").shouldNotBeNull()
            root.textContent.orEmpty() shouldContain "Loading"
        }
    })
