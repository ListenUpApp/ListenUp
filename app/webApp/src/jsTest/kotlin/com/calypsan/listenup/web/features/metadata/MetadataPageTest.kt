package com.calypsan.listenup.web.features.metadata

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.CoverEntry
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import com.calypsan.listenup.client.presentation.metadata.SearchLoadState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

@Suppress("LongParameterList")
private fun page(
    state: MetadataUiState,
    onQuery: (String) -> Unit = {},
    onRegion: (MetadataLocale) -> Unit = {},
    onSearch: () -> Unit = {},
    onSelectMatch: (MetadataBook) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onToggleField: (MetadataField) -> Unit = {},
    onToggleAuthor: (String) -> Unit = {},
    onToggleNarrator: (String) -> Unit = {},
    onToggleSeries: (String) -> Unit = {},
    onToggleGenre: (String) -> Unit = {},
    onToggleMood: (String) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onSelectCover: (String?) -> Unit = {},
    onToggleChapter: (Int) -> Unit = {},
    onApplyChapterNames: () -> Unit = {},
    onApply: () -> Unit = {},
    onLeave: () -> Unit = {},
    reviewingChapters: Boolean = false,
    onReviewChapters: (Boolean) -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        MetadataPage(
            state = state,
            onQuery = onQuery,
            onRegion = onRegion,
            onSearch = onSearch,
            onSelectMatch = onSelectMatch,
            onClearSelection = onClearSelection,
            onToggleField = onToggleField,
            onToggleAuthor = onToggleAuthor,
            onToggleNarrator = onToggleNarrator,
            onToggleSeries = onToggleSeries,
            onToggleGenre = onToggleGenre,
            onToggleMood = onToggleMood,
            onToggleTag = onToggleTag,
            onSelectCover = onSelectCover,
            onToggleChapter = onToggleChapter,
            onApplyChapterNames = onApplyChapterNames,
            onApply = onApply,
            onLeave = onLeave,
            reviewingChapters = reviewingChapters,
            onReviewChapters = onReviewChapters,
        )
    }
    return host
}

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/** The checkbox whose wrapping label reads exactly [label]. */
private fun checkbox(
    host: HTMLElement,
    label: String,
): HTMLInputElement? =
    host
        .querySelectorAll(".f-check")
        .asList()
        .filterIsInstance<HTMLElement>()
        .firstOrNull { it.textContent?.trim() == label }
        ?.querySelector("input") as? HTMLInputElement

private fun results(host: HTMLElement) = host.querySelectorAll(".mdx-result").asList().filterIsInstance<HTMLButtonElement>()

private fun fields(host: HTMLElement) = host.querySelectorAll(".mdx-field").asList().filterIsInstance<HTMLElement>()

/**
 * The value shown on the row whose checkbox is labelled [label].
 *
 * ⛔ Not `querySelector(".mdx-field-v")`. The Cover row is first on the page, so a document-wide
 * lookup reads its value for every field — a spec that passed against the wrong row entirely.
 */
private fun fieldValue(
    host: HTMLElement,
    label: String,
): String? =
    fields(host)
        .firstOrNull { it.querySelector(".f-check")?.textContent?.trim() == label }
        ?.querySelector(".mdx-field-v")
        ?.textContent

/**
 * Match metadata — the Audible wizard.
 *
 * What these pin: the search phase says what it is matching and what it found; a match is a
 * *suggestion* and nothing is written until Apply; every field is its own decision and Apply is
 * refused when none are ticked; a field that came from another provider says so; and the
 * chapter-name offer is count-gated, never force-aligned.
 */
class MetadataPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        // MARK: search

        test("the search phase says which book is being matched") {
            val host = page(searchState(currentTitle = "Mistborn", currentAuthor = "Brandon Sanderson"))

            host.querySelector(".mdx-ctx")?.textContent shouldContain "Mistborn"
            host.querySelector(".mdx-ctx")?.textContent shouldContain "by Brandon Sanderson"
        }

        test("the query is seeded, and typing reports the change") {
            val typed = mutableListOf<String>()
            val host = page(searchState(query = "The Way of Kings"), onQuery = { typed += it })

            val field = host.querySelector("#mdx-query") as HTMLInputElement
            field.value shouldBe "The Way of Kings"
            field.value = "Mistborn"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            typed shouldContainExactly listOf("Mistborn")
        }

        test("submitting the form searches") {
            val searches = mutableListOf<Unit>()
            val host = page(searchState(), onSearch = { searches += Unit })

            button(host, "Search Audible").shouldNotBeNull().click()
            awaitFrame()

            searches.size shouldBe 1
        }

        test("a search cannot be started twice, or on an empty query") {
            button(page(searchState(loadState = SearchLoadState.InFlight)), "Searching…")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
            button(page(searchState(query = "")), "Search Audible")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("a failed search is announced") {
            val host = page(searchState(loadState = SearchLoadState.Failed("Search timed out.")))

            val alert = host.querySelector(".mdx-err").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "Search timed out."
        }

        // ⛔ Names the region. An empty result is usually the wrong market, not a missing book, and
        // "try again" without saying that sends the reader nowhere.
        test("no matches points at the region, not just at the query") {
            val host =
                page(searchState(loadState = SearchLoadState.Loaded(emptyList()), region = MetadataLocale("de")))

            host.querySelector(".mdx-empty")?.textContent shouldContain "Germany"
        }

        test("every result is offered, with what tells two editions apart") {
            val host =
                page(
                    searchState(
                        loadState =
                            SearchLoadState.Loaded(
                                listOf(
                                    metadataBook(
                                        asin = "B1",
                                        title = "The Way of Kings",
                                        authors = listOf("Brandon Sanderson"),
                                        narrators = listOf("Kate Reading", "Michael Kramer"),
                                        runtimeMinutes = 2735,
                                    ),
                                    metadataBook(asin = "B2", title = "The Way of Kings Prime"),
                                ),
                            ),
                    ),
                )

            host.querySelector(".mdx-count")?.textContent shouldBe "2 matches found"
            results(host).size shouldBe 2
            results(host)[0].textContent.shouldNotBeNull().let {
                it shouldContain "by Brandon Sanderson"
                it shouldContain "Narrated by Kate Reading, Michael Kramer"
                it shouldContain "45h 35m"
                it shouldContain "B1"
            }
        }

        test("one match is counted in the singular") {
            val host = page(searchState(loadState = SearchLoadState.Loaded(listOf(metadataBook()))))

            host.querySelector(".mdx-count")?.textContent shouldBe "1 match found"
        }

        test("picking a result reports that edition") {
            val picked = mutableListOf<String>()
            val one = metadataBook(asin = "B-picked")
            val host =
                page(
                    searchState(loadState = SearchLoadState.Loaded(listOf(one))),
                    onSelectMatch = { picked += it.asin },
                )

            results(host).single().click()
            awaitFrame()

            picked shouldContainExactly listOf("B-picked")
        }

        // ⛔ Audible's own hosts. A referrer carrying this server's address is a detail of the
        // reader's private library leaking to a third party on every thumbnail.
        test("remote covers do not carry this server's address to Audible") {
            val host =
                page(
                    searchState(
                        loadState = SearchLoadState.Loaded(listOf(metadataBook(coverUrl = "https://m.media-amazon.com/x.jpg"))),
                    ),
                )

            host.querySelector(".mdx-result-c")?.getAttribute("referrerpolicy") shouldBe "no-referrer"
        }

        test("every region is offered, and the current one is marked") {
            val chosen = mutableListOf<String>()
            val host = page(searchState(region = MetadataLocale("uk")), onRegion = { chosen += it.region })

            button(host, "United Kingdom").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "true"
            button(host, "Germany").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "false"

            button(host, "Germany").shouldNotBeNull().click()
            awaitFrame()

            chosen shouldContainExactly listOf("de")
        }

        // MARK: preview

        test("a loading preview draws nothing it does not know yet") {
            val host = page(previewState(PreviewLoadState.Loading))

            host.querySelector(".mdx-skel").shouldNotBeNull()
            fields(host).size shouldBe 0
        }

        test("a failed preview is announced, and offers the way back to the results") {
            val back = mutableListOf<Unit>()
            val host =
                page(previewState(PreviewLoadState.Failed("Loading the match timed out.")), onClearSelection = { back += Unit })

            host.querySelector(".mdx-err")?.textContent shouldBe "Loading the match timed out."
            button(host, "Back to results").shouldNotBeNull().click()
            awaitFrame()

            back.size shouldBe 1
        }

        test("a book that is not in this region's catalogue says so instead of an empty form") {
            val host = page(previewState(readyPreview(previewNotFound = true)))

            host.querySelector(".mdx-err")?.textContent shouldContain "Try a different region"
            fields(host).size shouldBe 0
        }

        // ⛔ Says so INSTEAD OF the form, not as well as it. Sabotage removed the early return and
        // this spec passed anyway: the notice was there, and so was an empty form under it.
        test("a match carrying nothing says so rather than showing an empty form") {
            val host = page(previewState(readyPreview(preview = metadataBook(title = "", asin = "B0"))))

            host.querySelector(".mdx-empty")?.textContent shouldContain "No metadata available"
            fields(host).size shouldBe 0
            button(host, "Apply selected metadata").shouldBeNull()
        }

        test("each simple field shows what it would become, and reports its own toggle") {
            val toggled = mutableListOf<MetadataField>()
            val host =
                page(
                    previewState(
                        readyPreview(
                            preview =
                                metadataBook(
                                    title = "The Way of Kings",
                                    subtitle = "Book One",
                                    publisher = "Macmillan Audio",
                                    releaseDate = "2010-08-31",
                                    language = "en-US",
                                ),
                        ),
                    ),
                    onToggleField = { toggled += it },
                )

            checkbox(host, "Title").shouldNotBeNull()
            fieldValue(host, "Title") shouldBe "The Way of Kings"
            fieldValue(host, "Publisher") shouldBe "Macmillan Audio"

            listOf("Title", "Subtitle", "Publisher", "Release date", "Language").forEach {
                checkbox(host, it).shouldNotBeNull().click()
            }
            awaitFrame()

            toggled shouldContainExactly
                listOf(
                    MetadataField.TITLE,
                    MetadataField.SUBTITLE,
                    MetadataField.PUBLISHER,
                    MetadataField.RELEASE_DATE,
                    MetadataField.LANGUAGE,
                )
        }

        test("a field the match does not carry is not offered at all") {
            val host = page(previewState(readyPreview(preview = metadataBook(publisher = null, language = null))))

            checkbox(host, "Publisher").shouldBeNull()
            checkbox(host, "Language").shouldBeNull()
        }

        // ⛔ Provenance. A title merged in from iTunes is still worth taking, but the reader is
        // entitled to know it is not what the edition they picked says.
        test("a field that came from somewhere else says where") {
            val host =
                page(
                    previewState(
                        readyPreview(
                            preview = metadataBook(title = "The Way of Kings"),
                            fallbackSources = mapOf(BookField.TITLE to "iTunes"),
                        ),
                    ),
                )

            host.querySelector(".mdx-from")?.textContent shouldBe "from iTunes"
        }

        test("a field that came from the matched edition claims no other source") {
            val host = page(previewState(readyPreview(preview = metadataBook(title = "The Way of Kings"))))

            host.querySelector(".mdx-from").shouldBeNull()
        }

        // ⛔ Keyed by ASIN. The ViewModel's selection sets are ASIN sets, so a contributor Audible
        // gave no ASIN has no key to be selected by — a tick that cannot be recorded is worse than
        // no tick.
        test("each contributor and series is its own decision") {
            val authors = mutableListOf<String>()
            val narrators = mutableListOf<String>()
            val series = mutableListOf<String>()
            val host =
                page(
                    previewState(
                        readyPreview(
                            preview =
                                metadataBook(
                                    authors = listOf("Brandon Sanderson"),
                                    narrators = listOf("Kate Reading"),
                                    series = listOf(Triple("s1", "The Stormlight Archive", "1")),
                                ),
                        ),
                    ),
                    onToggleAuthor = { authors += it },
                    onToggleNarrator = { narrators += it },
                    onToggleSeries = { series += it },
                )

            checkbox(host, "Brandon Sanderson").shouldNotBeNull().click()
            checkbox(host, "Kate Reading").shouldNotBeNull().click()
            // The sequence rides in the label — "The Stormlight Archive" alone is a different series
            // membership from "The Stormlight Archive · 1".
            checkbox(host, "The Stormlight Archive · 1").shouldNotBeNull().click()
            awaitFrame()

            authors shouldContainExactly listOf("a0")
            narrators shouldContainExactly listOf("n0")
            series shouldContainExactly listOf("s1")
        }

        // ⛔ The CANDIDATES, not the match's own scraped lists. The ViewModel narrows Audible's
        // labels to the ones this library actually has, so a tick lands on a real genre.
        test("classification offers the library's own candidates, not every scraped label") {
            val genres = mutableListOf<String>()
            val host =
                page(
                    previewState(
                        readyPreview(
                            preview = metadataBook(genres = listOf("Epic Fantasy", "Nonsense Audible Invented")),
                            genreCandidates = listOf("Epic Fantasy"),
                        ),
                    ),
                    onToggleGenre = { genres += it },
                )

            checkbox(host, "Nonsense Audible Invented").shouldBeNull()
            checkbox(host, "Epic Fantasy").shouldNotBeNull().click()
            awaitFrame()

            genres shouldContainExactly listOf("Epic Fantasy")
        }

        test("cover options appear once the cover is being taken, and picking one reports it") {
            val picked = mutableListOf<String?>()
            val entries =
                listOf(
                    CoverEntry(url = "https://a/1.jpg", label = "Audible", resolution = "500×500"),
                    CoverEntry(url = "https://a/2.jpg", label = "iTunes HD", resolution = "7000×7000"),
                )
            val host =
                page(
                    previewState(
                        readyPreview(
                            selections = MetadataSelections(cover = true),
                            coverEntries = entries,
                            selectedCoverUrl = "https://a/1.jpg",
                        ),
                    ),
                    onSelectCover = { picked += it },
                )

            val options = host.querySelectorAll(".mdx-cover").asList().filterIsInstance<HTMLButtonElement>()
            options.map { it.getAttribute("aria-pressed") } shouldContainExactly listOf("true", "false")
            options[1].click()
            awaitFrame()

            picked shouldContainExactly listOf("https://a/2.jpg")
        }

        test("cover options are hidden while the cover is not being taken") {
            val host =
                page(
                    previewState(
                        readyPreview(
                            selections = MetadataSelections(cover = false),
                            coverEntries = listOf(CoverEntry("https://a/1.jpg", "Audible", null)),
                        ),
                    ),
                )

            host.querySelector(".mdx-cover").shouldBeNull()
        }

        // ⛔ Apply is the only thing that writes, and nothing ticked is nothing to write.
        test("Apply is refused when nothing is selected") {
            val nothing =
                MetadataSelections(
                    cover = false,
                    title = false,
                    subtitle = false,
                    description = false,
                    publisher = false,
                    releaseDate = false,
                    language = false,
                )
            button(page(previewState(readyPreview(selections = nothing))), "Apply selected metadata")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("Apply is offered once something is selected, and reports the press") {
            val applied = mutableListOf<Unit>()
            val host = page(previewState(readyPreview()), onApply = { applied += Unit })

            val apply = button(host, "Apply selected metadata").shouldNotBeNull()
            apply.hasAttribute("disabled") shouldBe false
            apply.click()
            awaitFrame()

            applied.size shouldBe 1
        }

        test("Apply says so while it is in flight, and cannot be pressed twice") {
            val host = page(previewState(readyPreview(isApplying = true)))

            button(host, "Applying…").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("a failed apply is announced") {
            val host = page(previewState(readyPreview(applyError = "The server refused that.")))

            host.querySelector(".mdx-err")?.textContent shouldBe "The server refused that."
        }

        test("a merged match names every source it drew from") {
            val host = page(previewState(readyPreview(contributingSources = listOf("Audible", "iTunes"))))

            host.querySelector(".mdx-merged")?.textContent shouldBe "Merged from Audible, iTunes"
        }

        // MARK: chapter names

        test("a book with nothing to name says nothing about chapters") {
            val host = page(previewState(readyPreview(chapterSuggestion = ChapterSuggestion.Unavailable)))

            host.querySelector(".mdx-chapters").shouldBeNull()
        }

        // ⛔ Count-gated, never force-aligned. A different count is a different edition, and mapping
        // 34 names onto 31 chapters puts the wrong name on every one from the first mismatch on.
        test("a chapter-count mismatch is refused, with both numbers") {
            val host =
                page(
                    previewState(
                        readyPreview(chapterSuggestion = ChapterSuggestion.CountMismatch(localCount = 31, audibleCount = 34)),
                    ),
                )

            val row = host.querySelector(".mdx-chapters").shouldNotBeNull()
            row.textContent.shouldNotBeNull() shouldContain "34 Audible chapters → your 31"
            button(host, "Review & apply chapter names").shouldBeNull()
        }

        test("matching counts offer a review, and opening it asks for the sheet") {
            val opened = mutableListOf<Boolean>()
            val host =
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 34))),
                    onReviewChapters = { opened += it },
                )

            host.querySelector(".mdx-chapters")?.textContent shouldContain "34 chapters matched"
            button(host, "Review & apply chapter names").shouldNotBeNull().click()
            awaitFrame()

            opened shouldContainExactly listOf(true)
        }

        test("the review sheet lists every name, and says the timings do not move") {
            val host =
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 3))),
                    reviewingChapters = true,
                )

            val dialog = host.querySelector("dialog").shouldNotBeNull()
            dialog.textContent.shouldNotBeNull() shouldContain "Timings stay the same."
            host.querySelectorAll(".mdx-chrow").asList().size shouldBe 3
            // Both names: what it becomes, and what it replaces.
            dialog.textContent.shouldNotBeNull() shouldContain "Chapter 1"
            dialog.textContent.shouldNotBeNull() shouldContain "Track 1"
        }

        test("each chapter name is its own decision") {
            val toggled = mutableListOf<Int>()
            val host =
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 3))),
                    onToggleChapter = { toggled += it },
                    reviewingChapters = true,
                )

            checkbox(host, "Chapter 2").shouldNotBeNull().click()
            awaitFrame()

            toggled shouldContainExactly listOf(1)
        }

        test("the sheet counts the selection, and says when it is all of them") {
            page(
                previewState(readyPreview(chapterSuggestion = availableChapters(count = 3))),
                reviewingChapters = true,
            ).querySelector(".mdx-chsel")?.textContent shouldBe "All 3 selected"

            page(
                previewState(readyPreview(chapterSuggestion = availableChapters(count = 3, selected = setOf(0)))),
                reviewingChapters = true,
            ).querySelector(".mdx-chsel")?.textContent shouldBe "1 of 3 selected"
        }

        test("applying names is refused when none are selected") {
            val host =
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 3, selected = emptySet()))),
                    reviewingChapters = true,
                )

            button(host, "Apply chapter names").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("applying names reports the press, and says so while it is in flight") {
            val applied = mutableListOf<Unit>()
            val host =
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 3))),
                    onApplyChapterNames = { applied += Unit },
                    reviewingChapters = true,
                )

            button(host, "Apply chapter names").shouldNotBeNull().click()
            awaitFrame()
            applied.size shouldBe 1

            button(
                page(
                    previewState(readyPreview(chapterSuggestion = availableChapters(count = 3, isApplying = true))),
                    reviewingChapters = true,
                ),
                "Applying…",
            ).shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("the sheet is not up until it is asked for") {
            val host = page(previewState(readyPreview(chapterSuggestion = availableChapters())))

            host.querySelector("dialog").shouldBeNull()
        }

        test("leaving the wizard reports it") {
            val left = mutableListOf<Unit>()
            val host = page(searchState(), onLeave = { left += Unit })

            button(host, "Back").shouldNotBeNull().click()
            awaitFrame()

            left.size shouldBe 1
        }
    })
