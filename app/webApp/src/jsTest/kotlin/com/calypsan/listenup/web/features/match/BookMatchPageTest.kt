package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.BookMatchEvent
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.FieldOptionUi
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.LabelSetUi
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.presentation.match.SuggestionUi
import com.calypsan.listenup.client.presentation.match.YourLabelUi
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

/** Everything the page asked the session to do, in order, as readable lines. */
internal class Calls {
    val said = mutableListOf<String>()

    operator fun plusAssign(line: String) {
        said += line
    }
}

/** A live page over [find] and [review], with every gesture written to [calls]. */
internal class MatchRig(
    find: FindUiState = results(),
    review: ReviewUiState = ReviewUiState.NoneChosen,
) {
    val find = MutableStateFlow(find)
    val review = MutableStateFlow(review)
    val events = Channel<BookMatchEvent>(Channel.BUFFERED)
    val calls = Calls()
    var applied = 0
    var compareOpened = 0
    var compareClosed = 0

    val session: BookMatchSession =
        fixedBookMatch(
            findState = this.find,
            reviewState = this.review,
            events = events.receiveAsFlow(),
            search = { calls += "search:$it" },
            searchByTitle = { calls += "searchByTitle" },
            chooseStoreForThisSearch = { calls += "store:${it.region}" },
            retry = { calls += "retry" },
            pick = { calls += "pick:${it.refs.single().id}" },
            backToResults = { calls += "back" },
            useTwoPane = { calls += "twoPane:$it" },
            setFieldTicked = { f, on -> calls += "tick:$f:$on" },
            chooseSource = { f, c -> calls += "source:$f:${(c as? FieldChoice.Option)?.optionId ?: "keep"}" },
            chooseCover = { calls += "cover:${(it as? ImageChoice.Candidate)?.optionId ?: "keep"}" },
            removeYourLabel = { k, l -> calls += "remove:$k:$l" },
            restoreYourLabel = { k, l -> calls += "restore:$k:$l" },
            toggleSuggestion = { k, l -> calls += "suggest:$k:$l" },
            setChapterNamesIncluded = { calls += "chapters:$it" },
            toggleChapter = { calls += "chapter:$it" },
            apply = { calls += "apply" },
        )

    fun mount(
        view: MatchView = MatchView.Find,
        viewerId: String? = "u-me",
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        hosts += host
        renderComposable(root = host) {
            BookMatchPage(
                session = session,
                bookId = "b-1",
                viewerId = viewerId,
                view = view,
                onOpenCompare = { compareOpened++ },
                onCloseCompare = { compareClosed++ },
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = { applied++ },
            )
        }
        return host
    }
}

internal fun HTMLElement.buttonNamed(name: String): HTMLButtonElement? =
    querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { (it.getAttribute("aria-label") ?: it.textContent?.trim()) == name }

private fun HTMLElement.text(selector: String): String? = querySelector(selector)?.textContent?.trim()

private fun HTMLElement.texts(selector: String): List<String> =
    querySelectorAll(selector).asList().filterIsInstance<HTMLElement>().map { it.textContent.orEmpty().trim() }

private fun HTMLElement.live(): String = text("#$LIVE_ID").orEmpty()

private fun HTMLElement.checkbox(name: String): HTMLInputElement? =
    querySelectorAll("input[type=checkbox]")
        .asList()
        .filterIsInstance<HTMLInputElement>()
        .firstOrNull { it.getAttribute("aria-label") == name || it.parentElement?.textContent?.trim() == name }

private fun focusedId(): String? = (document.activeElement as? HTMLElement)?.id

/**
 * Match details on web — Find, Compare editions and Review over one shared session (W-01…W-05).
 *
 * What these pin: Find says what it found and why each match ranks where it does; every failure says
 * what happened and offers a way on; Review shows the sections in canvas order with only changes
 * ticked; the Apply bar says what Apply will write; and the keyboard path never drops to `<body>`.
 */
class BookMatchPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        // MARK: Find

        test("results come in Strong match and Maybe, each row saying why it ranks there") {
            val host = MatchRig().mount()
            awaitFrame()

            host.texts(".bmx-group-t").map { it.filter { c -> !c.isDigit() } } shouldContainExactly
                listOf("Strong match", "Maybe")
            val best = host.querySelectorAll(".bmx-row").asList().filterIsInstance<HTMLElement>().first()
            best.text(".bmx-badges") shouldContain "Best match"
            best.text(".bmx-row-meta") shouldBe "Ray Porter · 16h 10m · 2021 · Unabridged"
            best.texts(".bmx-reason") shouldContainExactly listOf("Same narrator", "Same length", "36 chapters")
            best.querySelectorAll(".bmx-reason.is-strong svg").length shouldBe 3
            best.text(".bmx-row-found") shouldBe "Found in Audible and Hardcover"
            host.text(".bmx-note") shouldBe "Started from your Audible link, then title, author and length."
        }

        test("a Maybe row's reasons carry no green check: Maybe is not a recommendation") {
            val host = MatchRig().mount()
            awaitFrame()

            val maybe = host.querySelectorAll(".bmx-row").asList().filterIsInstance<HTMLElement>().last()
            maybe.texts(".bmx-reason") shouldContainExactly listOf("6h 26m shorter")
            maybe.querySelector(".bmx-reason.is-strong").shouldBeNull()
        }

        test("Your copy is shown from this device, and the current link is badged") {
            val rig = MatchRig(find = results(strong = listOf(candidate(isBest = true, isCurrentLink = true))))
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-copy-m") shouldBe "16h 10m · Ray Porter · 36 chapters"
            host.text(".bmx-badges") shouldContain "Your current link"
        }

        test("the search is seeded with the title and searches on Enter, not per keystroke") {
            val rig = MatchRig()
            val host = rig.mount()
            awaitFrame()

            val field = host.querySelector("#$SEARCH_ID") as HTMLInputElement
            field.value shouldBe "Project Hail Mary"
            field.value = "Hail Mary Weir"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            rig.calls.said shouldBe listOf("twoPane:true")

            host.buttonNamed("Search").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said shouldContainExactly listOf("twoPane:true", "search:Hail Mary Weir")
        }

        test("the store is one menu button, and picking a store searches it for this search only") {
            val rig = MatchRig()
            val host = rig.mount()
            awaitFrame()

            val trigger = host.querySelector(".bmx-store-b") as HTMLElement
            trigger.textContent?.trim() shouldBe "Audible store: United States"
            trigger.getAttribute("aria-label") shouldBe "Audible store: United States. Just this search"
            trigger.click()
            awaitFrame()
            val items = host.querySelectorAll("[role=menuitemradio]").asList().filterIsInstance<HTMLElement>()
            items.map { it.getAttribute("aria-checked") } shouldContainExactly listOf("true", "false", "false")
            items[1].click()
            awaitFrame()

            rig.calls.said.last() shouldBe "store:uk"
        }

        test("a search in flight keeps the last results on screen, marked busy, and says so") {
            val host = MatchRig(find = searching(previous = results())).mount()
            awaitFrame()

            host.querySelectorAll(".bmx-row").length shouldBe 2
            host.querySelector(".bmx-rows")?.getAttribute("aria-busy") shouldBe "true"
            host.live() shouldBe "Searching…"
        }

        test("the live region counts the matches once they arrive") {
            val rig = MatchRig(find = searching())
            val host = rig.mount()
            awaitFrame()
            host.live() shouldBe "Searching…"

            rig.find.value = results()
            awaitFrame()
            awaitFrame()

            host.live() shouldBe "2 matches"
        }

        test("when one source fails, the banner names it and Retry asks it again") {
            val rig = MatchRig(find = results(partialFailure = PartialFailure(listOf(HARDCOVER), listOf(AUDIBLE, ITUNES))))
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-banner p") shouldBe "Hardcover didn't answer, so these results are from Audible and iTunes."
            host.buttonNamed("Retry Hardcover").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said.last() shouldBe "retry"
        }

        // MARK: failures (W-05)

        test("offline says so and offers Retry") {
            val rig = MatchRig(find = failed(FindFailure.Offline))
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "You're offline"
            host.text(".bmx-failure") shouldContain "Matching needs the server, and the server needs its sources."
            host.buttonNamed("Retry").shouldNotBeNull().click()
            awaitFrame()
            rig.calls.said.last() shouldBe "retry"
            host.live() shouldContain "You're offline"
        }

        test("a timeout names the source and says nothing was changed") {
            val host = MatchRig(find = failed(FindFailure.TimedOut(AUDIBLE))).mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "Audible didn't answer in time"
            host.text(".bmx-failure") shouldContain "Nothing was changed. This usually clears in a moment."
            host.buttonNamed("Retry").shouldNotBeNull()
        }

        test("a source that failed outright names itself and offers Retry") {
            val host = MatchRig(find = failed(FindFailure.SourceFailed(HARDCOVER))).mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "Hardcover didn't answer"
            host.text(".bmx-failure") shouldContain "Nothing was changed. Try again in a moment."
            host.buttonNamed("Retry").shouldNotBeNull()
        }

        test("a rate limit counts down on a Retry that cannot be pressed yet") {
            val rig = MatchRig(find = failed(FindFailure.RateLimited(HARDCOVER, 30)))
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "Hardcover asked us to slow down"
            host.text(".bmx-failure") shouldContain "Try again in 30 seconds."
            host.text(".bmx-failure") shouldContain "Nothing was changed."
            val retry = host.buttonNamed("Retry in 0:30").shouldNotBeNull()
            retry.getAttribute("aria-disabled") shouldBe "true"
            retry.click()
            awaitFrame()
            rig.calls.said shouldBe listOf("twoPane:true")
        }

        test("the countdown is announced when it starts and when it ends, never each second") {
            val rig = MatchRig(find = failed(FindFailure.RateLimited(HARDCOVER, 30)))
            val host = rig.mount()
            awaitFrame()
            val started = host.live()
            started shouldContain "Hardcover asked us to slow down"

            rig.find.value = failed(FindFailure.RateLimited(HARDCOVER, 29))
            awaitFrame()
            awaitFrame()
            host.live() shouldBe started

            rig.find.value = failed(FindFailure.RateLimited(HARDCOVER, 0))
            awaitFrame()
            awaitFrame()
            host.live() shouldBe "You can retry Hardcover now."
            host.buttonNamed("Retry").shouldNotBeNull().getAttribute("aria-disabled").shouldBeNull()
        }

        test("not found in a store offers at most two other stores and Search by title") {
            val rig =
                MatchRig(
                    find =
                        failed(
                            FindFailure.NotFoundInStore(AUDIBLE, UK, listOf(US, MetadataLocale("au"), CA)),
                        ),
                )
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "No match in the United Kingdom store"
            host.texts(".bmx-fail-acts button") shouldContainExactly
                listOf("Try United States", "Try Australia", "Search by title")
            host.buttonNamed("Try Australia").shouldNotBeNull().click()
            host.buttonNamed("Search by title").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said.takeLast(2) shouldContainExactly listOf("store:au", "searchByTitle")
        }

        test("nothing found offers Search by title") {
            val host = MatchRig(find = failed(FindFailure.NothingFound)).mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "No matches"
            host.buttonNamed("Search by title").shouldNotBeNull()
        }

        test("an unexpected failure says the error's own words") {
            val host = MatchRig(find = failed(FindFailure.Unexpected(MetadataError.Malformed()))).mount()
            awaitFrame()

            host.text(".bmx-failure") shouldContain "Something went wrong"
            host.text(".bmx-failure") shouldContain "The external metadata response was malformed."
            host.buttonNamed("Retry").shouldNotBeNull()
        }

        // MARK: picking, and the keyboard path

        test("a two-pane page tells the session so, which opens the best match") {
            val rig = MatchRig()
            rig.mount()
            awaitFrame()

            rig.calls.said.first() shouldBe "twoPane:true"
        }

        test("picking a match opens it and lands focus on the Review heading") {
            val rig = MatchRig()
            val host = rig.mount()
            awaitFrame()

            (host.querySelectorAll(".bmx-row").asList()[1] as HTMLElement).click()
            awaitFrame()
            awaitFrame()

            rig.calls.said.last() shouldBe "pick:B2"
            focusedId() shouldBe REVIEW_HEADING_ID
        }

        test("Back to results returns focus to the row that was open") {
            val chosen = candidate(id = "B2", title = "Other")
            val rig =
                MatchRig(
                    find = results(maybe = listOf(chosen), pickedKey = chosen.key),
                    review = ready(candidate = chosen),
                )
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Back to results").shouldNotBeNull().click()
            rig.review.value = ReviewUiState.NoneChosen
            awaitFrame()
            awaitFrame()

            rig.calls.said.last() shouldBe "back"
            focusedId() shouldBe rowId(chosen)
            host.querySelector("#${rowId(chosen)}")?.getAttribute("aria-current") shouldBe "true"
        }

        test("Skip to Apply comes straight after the Review heading and lands on Apply") {
            val host = MatchRig(review = ready()).mount()
            awaitFrame()

            val heading = host.querySelector("#$REVIEW_HEADING_ID") as HTMLElement
            (heading.nextElementSibling as HTMLElement).textContent?.trim() shouldBe "Skip to Apply"
            host.buttonNamed("Skip to Apply").shouldNotBeNull().click()
            awaitFrame()

            focusedId() shouldBe APPLY_ID
        }

        // MARK: Review (W-03)

        test("Review's sections come in canvas order, each under its own heading") {
            val host = MatchRig(review = ready()).mount()
            awaitFrame()

            host.texts(".bmx-sec-t") shouldContainExactly
                listOf(
                    "Cover",
                    "Changes",
                    "Fills a gap",
                    "You edited this",
                    "Genres & moods",
                    "Chapter names",
                    "Already the same",
                )
        }

        test("an empty section is not rendered") {
            val host =
                MatchRig(
                    review =
                        ready(
                            fillsGap = emptyList(),
                            youEdited = emptyList(),
                            chapterNames = ChapterNamesUi.Hidden,
                            alreadySame = emptyList(),
                        ).copy(lengthAlreadySame = false),
                ).mount()
            awaitFrame()

            host.texts(".bmx-sec-t") shouldContainExactly listOf("Cover", "Changes", "Genres & moods")
        }

        test("only what changes is ticked; your own edit is left unticked and says who made it") {
            val host = MatchRig(review = ready()).mount()
            awaitFrame()

            host.checkbox("Description, proposed from Audible, changes yours").shouldNotBeNull().checked shouldBe true
            host.checkbox("Release date, proposed from Audible, fills a gap").shouldNotBeNull().checked shouldBe true
            val edited = host.querySelector("[data-field=SUBTITLE]") as HTMLElement
            (edited.querySelector("input[type=checkbox]") as HTMLInputElement).checked shouldBe false
            edited.text(".bmx-edited") shouldBe "You edited this"
            edited.text(".bmx-edited-note") shouldBe "Edited by you, 12 Sep. Kept unless you tick it."
        }

        test("someone else's edit is named, and an undated one says no date") {
            val other =
                field(BookField.SUBTITLE, FieldState.USER_EDITED, handEdit = HandEdit("u-other", "Ana", at = null))
            val host = MatchRig(review = ready(youEdited = listOf(other))).mount()
            awaitFrame()

            host.text(".bmx-edited-note") shouldBe "Edited by Ana. Kept unless you tick it."
        }

        test("ticking and the source switch report the field") {
            val twoSources =
                field(
                    BookField.DESCRIPTION,
                    extraOptions =
                        listOf(
                            FieldOptionUi(
                                "DESCRIPTION-2",
                                FieldValue.Text("Other"),
                                listOf(HARDCOVER),
                            ),
                        ),
                )
            val rig = MatchRig(review = ready(changes = listOf(twoSources)))
            val host = rig.mount()
            awaitFrame()

            host.checkbox("Description, proposed from Audible, changes yours").shouldNotBeNull().click()
            val segments = host.querySelectorAll(".bmx-src input[type=radio]").asList().filterIsInstance<HTMLInputElement>()
            host.texts(".bmx-seg") shouldContainExactly listOf("Audible", "Hardcover", "Keep yours")
            segments[1].click()
            segments[2].click()
            awaitFrame()

            rig.calls.said.takeLast(3) shouldContainExactly
                listOf("tick:DESCRIPTION:false", "source:DESCRIPTION:DESCRIPTION-2", "source:DESCRIPTION:keep")
        }

        test("the cover is one radio group with Keep current first") {
            val rig = MatchRig(review = ready())
            val host = rig.mount()
            awaitFrame()

            val radios = host.querySelectorAll(".bmx-covers input[type=radio]").asList().filterIsInstance<HTMLInputElement>()
            radios.map { it.getAttribute("aria-label") } shouldContainExactly
                listOf("Keep current cover", "Cover from Hardcover, 1400 by 1400", "Cover from Audible, 500 by 500")
            radios[1].checked shouldBe true
            radios[0].click()
            awaitFrame()

            rig.calls.said.last() shouldBe "cover:keep"
        }

        test("your genres can be removed and restored; suggestions toggle and say where they came from") {
            val rig =
                MatchRig(
                    review =
                        ready(
                            genres =
                                LabelSetUi(
                                    yours =
                                        listOf(
                                            YourLabelUi("Science Fiction", false),
                                            YourLabelUi("Thriller", true),
                                        ),
                                    suggested =
                                        listOf(
                                            SuggestionUi(
                                                "Space Opera",
                                                listOf(AUDIBLE, HARDCOVER),
                                                selected = false,
                                            ),
                                        ),
                                ),
                        ),
                )
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Remove Science Fiction").shouldNotBeNull().click()
            host.buttonNamed("Keep Thriller").shouldNotBeNull().click()
            val chip = host.buttonNamed("Space Opera, from Audible and Hardcover").shouldNotBeNull()
            chip.getAttribute("aria-pressed") shouldBe "false"
            chip.click()
            awaitFrame()

            rig.calls.said.takeLast(3) shouldContainExactly
                listOf(
                    "remove:${LabelKind.GENRES}:Science Fiction",
                    "restore:${LabelKind.GENRES}:Thriller",
                    "suggest:${LabelKind.GENRES}:Space Opera",
                )
            host.text(".bmx-sec[aria-labelledby=$SECTION_LABELS]") shouldContain "Tags are yours. Matching never changes them."
        }

        test("chapter names: include them or not, a sentence of counts, the first rows and Show all") {
            val rig = MatchRig(review = ready())
            val host = rig.mount()
            awaitFrame()

            val section = host.querySelector(".bmx-sec[aria-labelledby=$SECTION_CHAPTERS]") as HTMLElement
            section.textContent shouldContain "16 of 36 chapters get names from Audible. The other 20 already match."
            section.querySelectorAll(".bmx-chapter").length shouldBe 3
            host.checkbox("Apply chapter names").shouldNotBeNull().click()
            host.checkbox("Chapter 1: Chapter 1 becomes Name 1").shouldNotBeNull().click()
            host.buttonNamed("Show all 16").shouldNotBeNull().click()
            awaitFrame()

            section.querySelectorAll(".bmx-chapter").length shouldBe 16
            rig.calls.said.takeLast(2) shouldContainExactly listOf("chapters:false", "chapter:0")
        }

        test("a different chapter count is said, never offered") {
            val host = MatchRig(review = ready(chapterNames = ChapterNamesUi.CountMismatch(AUDIBLE, 36, 40))).mount()
            awaitFrame()

            val section = host.querySelector(".bmx-sec[aria-labelledby=$SECTION_CHAPTERS]") as HTMLElement
            section.textContent shouldContain "Audible has 40 chapters and yours has 36, so its names can't be matched."
            section.querySelector("input").shouldBeNull()
        }

        test("Already the same is collapsed, and counts Length when it matches") {
            val host = MatchRig(review = ready()).mount()
            awaitFrame()

            host.text(".bmx-same summary") shouldBe "3 fields already match"
            host.text(".bmx-same p") shouldBe "Title, Authors, Length"
            (host.querySelector(".bmx-same") as HTMLElement).hasAttribute("open") shouldBe false
        }

        test("long text shows three lines and Read all") {
            val long = "x".repeat(400)
            val host =
                MatchRig(
                    review =
                        ready(
                            changes =
                                listOf(
                                    field(
                                        BookField.DESCRIPTION,
                                        proposed = FieldValue.Text("<p>$long</p>"),
                                    ),
                                ),
                        ),
                ).mount()
            awaitFrame()

            host.querySelector(".is-clamped").shouldNotBeNull().textContent shouldNotContain "<p>"
            host.buttonNamed("Read all").shouldNotBeNull().click()
            awaitFrame()
            host.querySelector(".is-clamped").shouldBeNull()
        }

        // MARK: the Apply bar

        test("the Apply bar says what Apply will write, and follows the choices in place") {
            val rig = MatchRig(review = ready())
            val host = rig.mount()
            awaitFrame()
            host.text(".bmx-bar-sum") shouldBe "5 fields · cover · 16 chapter names"

            rig.review.value = ready(applyBar = ApplySummary(fieldCount = 1, coverChanges = false, chapterNameCount = 0))
            awaitFrame()
            host.text(".bmx-bar-sum") shouldBe "1 field"

            rig.review.value = ready(applyBar = ApplySummary(0, false, 0))
            awaitFrame()
            host.text(".bmx-bar-sum") shouldBe "Nothing selected"
            host.querySelector("#$APPLY_ID")?.getAttribute("aria-disabled") shouldBe "true"
        }

        test("Apply applies, says Applying… while it runs, and ignores a second press") {
            val rig = MatchRig(review = ready())
            val host = rig.mount()
            awaitFrame()

            (host.querySelector("#$APPLY_ID") as HTMLElement).click()
            rig.review.value = ready(applying = true)
            awaitFrame()
            awaitFrame()
            (host.querySelector("#$APPLY_ID") as HTMLElement).click()
            awaitFrame()

            rig.calls.said.count { it == "apply" } shouldBe 1
            host.text("#$APPLY_ID") shouldBe "Applying…"
            host.live() shouldBe "Applying…"
        }

        test("a failed Apply says so above the bar, with Nothing was changed said once") {
            val host = MatchRig(review = ready(applyError = MetadataError.CoverDownloadFailed())).mount()
            awaitFrame()

            host.text(".bmx-apply .bmx-err") shouldBe "The chosen cover couldn't be downloaded. Nothing was changed."
        }

        test("Applied hands back to Book Detail") {
            val rig = MatchRig(review = ready())
            rig.mount()
            awaitFrame()

            rig.events.send(BookMatchEvent.Applied(MatchReceipt("r-1", 0, emptyList(), undoable = true)))
            awaitFrame()

            rig.applied shouldBe 1
        }

        test("a Review that reloaded says the book changed") {
            val rig = MatchRig(review = ready())
            val host = rig.mount()
            awaitFrame()

            rig.events.send(BookMatchEvent.ReviewReloaded)
            awaitFrame()
            awaitFrame()

            host.text(".bmx-review .bmx-err") shouldBe "This book changed while you were reviewing. Check the changes again."
        }

        // MARK: Compare editions (W-02)

        test("Compare editions sets every match beside your copy, from Find's data alone") {
            val rig =
                MatchRig(
                    find =
                        results(
                            maybe = listOf(candidate(id = "B2", narrators = emptyList(), chapterCount = null, durationMs = null, reasons = emptyList())),
                        ),
                )
            val host = rig.mount(view = MatchView.Compare)
            awaitFrame()

            host.texts(".bmx-cmp tbody th[scope=row]") shouldContainExactly
                listOf("Length", "Narrator", "Chapters", "Year", "Format", "Store", "Found in")
            val narratorRow = host.querySelectorAll(".bmx-cmp tbody tr").asList()[1] as HTMLElement
            narratorRow.texts("td").last() shouldBe "Not listed"
            rig.calls.said.none { it.startsWith("search") } shouldBe true
        }

        test("Review this match names its match, picks it and goes back to Review") {
            val rig = MatchRig()
            val host = rig.mount(view = MatchView.Compare)
            awaitFrame()

            host.buttonNamed("Review this match, Project Hail Mary, Audible and Hardcover").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said.last() shouldBe "pick:B1"
            rig.compareClosed shouldBe 1
        }

        test("Compare editions in Find opens the comparison") {
            val rig = MatchRig()
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Compare editions").shouldNotBeNull().click()
            awaitFrame()

            rig.compareOpened shouldBe 1
        }

        test("the breadcrumb leads Library › book › Match details") {
            val host = MatchRig().mount()
            awaitFrame()

            host.texts("nav.crumb li").map { it.removePrefix("/").trim() } shouldContainExactly
                listOf("Library", "Project Hail Mary", "Match details")
        }
    })
