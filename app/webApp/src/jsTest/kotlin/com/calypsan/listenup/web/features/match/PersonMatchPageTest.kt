package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.Element
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

/** A live person page over [find] and [review], with every gesture written to [calls]. */
internal class PersonMatchRig(
    find: PersonFindUiState = authorResults(),
    review: PersonReviewUiState = PersonReviewUiState.NoneChosen,
) {
    val find = MutableStateFlow(find)
    val review = MutableStateFlow(review)
    val events = Channel<PersonMatchEvent>(Channel.BUFFERED)
    val calls = Calls()
    var applied = 0
    var editedByHand = 0
    var openedContributor = 0

    val session: PersonMatchSession =
        fixedPersonMatch(
            findState = this.find,
            reviewState = this.review,
            events = events.receiveAsFlow(),
            search = { calls += "search:$it" },
            switchRole = { calls += "role:$it" },
            retry = { calls += "retry" },
            pick = { calls += "pick:${it.refs.single().id}" },
            backToResults = { calls += "back" },
            useTwoPane = { calls += "twoPane:$it" },
            setPhotoTicked = { calls += "photoTicked:$it" },
            choosePhoto = { calls += "photo:${(it as? ImageChoice.Candidate)?.optionId ?: "keep"}" },
            setBiographyTicked = { calls += "bioTicked:$it" },
            chooseBiographySource = { calls += "bio:${(it as? FieldChoice.Option)?.optionId ?: "keep"}" },
            apply = { calls += "apply" },
        )

    @androidx.compose.runtime.Composable
    fun Page() {
        PersonMatchPage(
            session = session,
            contributorId = "c-1",
            viewerId = "u-me",
            onOpenLibrary = {},
            onOpenContributor = { openedContributor++ },
            onEditByHand = { editedByHand++ },
            onApplied = { applied++ },
        )
    }

    fun mount(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        hosts += host
        renderComposable(root = host) { Page() }
        return host
    }
}

private fun HTMLElement.text(selector: String): String? = querySelector(selector)?.textContent?.trim()

private fun Element.texts(selector: String): List<String> =
    querySelectorAll(selector).asList().filterIsInstance<HTMLElement>().map { it.textContent.orEmpty().trim() }

private fun HTMLElement.live(): String = text("#$LIVE_ID").orEmpty()

private fun HTMLElement.rows(): List<HTMLElement> =
    querySelectorAll(".bmx-row").asList().filterIsInstance<HTMLElement>()

private fun Element.inputNamed(name: String): HTMLInputElement? =
    querySelectorAll("input")
        .asList()
        .filterIsInstance<HTMLInputElement>()
        .firstOrNull { it.getAttribute("aria-label") == name || it.parentElement?.textContent?.trim() == name }

private fun focusedId(): String? = (document.activeElement as? HTMLElement)?.id

/**
 * Person Match details on web (W-06, W-07): Find as author or narrator, the coverage note and Your library,
 * people rows that say who each one is, No profiles with Edit by hand, and Review with the photo and the
 * biography chosen apart under one Apply. The keyboard path never drops to `<body>`.
 */
class PersonMatchPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        // MARK: Find

        test("author Find: the title, the person, Your library, the steps and the count") {
            val host = PersonMatchRig().mount()
            awaitFrame()

            host.text(".page-t") shouldBe "Match details"
            host.text(".page-h p") shouldBe "Andy Weir · author"
            host.text(".pmx-lib-t") shouldBe
                "Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis"
            host.querySelectorAll(".pmx-lib .bmx-art").length shouldBe 3
            host.text(".bmx-note") shouldBe "Started from the 3 books Andy Weir wrote in your library."
            host.text(".bmx-find .bmx-count") shouldBe "3 people"
            host.querySelector(".pmx-coverage").shouldBeNull()
        }

        test("the search is a labelled field in a search form, and searches on submit") {
            val rig = PersonMatchRig()
            val host = rig.mount()
            awaitFrame()

            host.querySelector("form[role=search]").shouldNotBeNull()
            val field = host.querySelector("#$SEARCH_ID") as HTMLInputElement
            host.querySelector("label[for=$SEARCH_ID]")?.textContent?.trim() shouldBe "Search for a person"
            field.value shouldBe "Andy Weir"
            field.value = "Andrew Weir"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            host.buttonNamed("Search").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said.last() shouldBe "search:Andrew Weir"
        }

        test("rows come in Strong match and Maybe, each saying role, works, your library and sources") {
            val host = PersonMatchRig().mount()
            awaitFrame()

            host.texts(".bmx-group-t").map { it.filter { c -> !c.isDigit() } } shouldContainExactly
                listOf("Strong match", "Maybe")
            val best = host.rows().first()
            best.text(".bmx-row-t") shouldBe "Andy Weir"
            best.text(".bmx-row-meta") shouldBe "Author · The Martian, Artemis"
            best.text(".pmx-lib-line") shouldBe "Wrote 3 books in your library"
            best.text(".bmx-row-found") shouldBe "Found in Audible and Hardcover"
            best.getAttribute("aria-label") shouldBe
                "Best match. Andy Weir. Author · The Martian, Artemis. Wrote 3 books in your library. " +
                "Found in Audible and Hardcover."
            host.rows().map { it.getAttribute("aria-pressed") } shouldContainExactly listOf("false", "false", "false")
            host.rows()[2].text(".bmx-row-meta") shouldBe "Author · 1 book"
            host.rows()[2].text(".pmx-lib-line") shouldBe "No books in your library"
        }

        test("the open row is pressed") {
            val chosen = person(isBest = true)
            val host = PersonMatchRig(find = authorResults(pickedKey = chosen.key)).mount()
            awaitFrame()

            host.rows().map { it.getAttribute("aria-pressed") } shouldContainExactly listOf("true", "false", "false")
        }

        test("narrator Find: the coverage note, the narrator search and a Different role row") {
            val host = PersonMatchRig(find = narratorResults()).mount()
            awaitFrame()

            host.text(".page-h p") shouldBe "Ray Porter · narrator"
            host.text(".pmx-coverage") shouldBe "Audible has no narrator profiles, so this search uses Hardcover."
            host.querySelector("label[for=$SEARCH_ID]")?.textContent?.trim() shouldBe "Search for a narrator"
            host.text(".pmx-lib-t") shouldBe "Narrated 5 books in your library: Project Hail Mary, Bobiverse"
            host.text(".bmx-note") shouldBe "Started from the 5 books Ray Porter narrates in your library."
            val other = host.rows().last()
            other.text(".bmx-row-meta") shouldBe "Author · 1 book · Not a narrator"
            other.text(".pmx-role-chip") shouldBe "Different role"
            other.querySelector(".pmx-lib-line").shouldBeNull()
            other.getAttribute("aria-label").shouldNotBeNull() shouldContain "Different role"
        }

        test("Match as is a radio group, and choosing a role switches to it") {
            val rig = PersonMatchRig(find = narratorResults())
            val host = rig.mount()
            awaitFrame()

            val group = host.querySelector("fieldset.pmx-role").shouldNotBeNull()
            group.querySelector("legend")?.textContent?.trim() shouldBe "Match as"
            host.inputNamed("As narrator").shouldNotBeNull().checked shouldBe true
            host.inputNamed("As author").shouldNotBeNull().click()
            awaitFrame()

            rig.calls.said.last() shouldBe "role:AUTHOR"
        }

        test("a search in flight keeps the last people, busy, and the live region counts them when they come") {
            val rig = PersonMatchRig(find = personSearching(previous = authorResults()))
            val host = rig.mount()
            awaitFrame()

            host.rows().size shouldBe 3
            host.querySelector(".bmx-rows")?.getAttribute("aria-busy") shouldBe "true"
            host.live() shouldBe "Searching…"

            rig.find.value = authorResults()
            awaitFrame()
            awaitFrame()

            host.live() shouldBe "3 people"
        }

        test("no profiles says so for the role, keeps the search, and offers Edit by hand") {
            val rig = PersonMatchRig(find = noProfiles())
            val host = rig.mount()
            awaitFrame()
            awaitFrame()

            host.text(".bmx-find .empty :is(h2, h3, h4)") shouldBe "No source has a profile for this narrator"
            host.text(".bmx-find .empty") shouldContain "You can add their photo and biography yourself."
            host.querySelector("#$SEARCH_ID").shouldNotBeNull()
            host.inputNamed("As author").shouldNotBeNull()
            host.live() shouldBe "No source has a profile for this narrator"
            host.buttonNamed("Edit by hand").shouldNotBeNull().click()

            rig.editedByHand shouldBe 1
        }

        test("a failure says what happened and Retry asks again") {
            val rig = PersonMatchRig(find = personFailed(FindFailure.SourceFailed(HARDCOVER)))
            val host = rig.mount()
            awaitFrame()
            awaitFrame()

            host.text(".bmx-failure :is(h2, h3, h4)") shouldBe "Hardcover didn't answer"
            host.live() shouldBe "Hardcover didn't answer. Nothing was changed. Try again in a moment."
            host.buttonNamed("Retry").shouldNotBeNull().click()

            rig.calls.said.last() shouldBe "retry"
        }

        test("when one source fails, the banner names it") {
            val partial =
                com.calypsan.listenup.client.presentation.match
                    .PartialFailure(listOf(HARDCOVER), listOf(AUDIBLE))
            val host = PersonMatchRig(find = authorResults(partialFailure = partial)).mount()
            awaitFrame()

            host.text(".bmx-banner p") shouldBe "Hardcover didn't answer, so these results are from Audible."
        }

        // MARK: the keyboard path

        test("a two-pane page tells the session so") {
            val rig = PersonMatchRig()
            rig.mount()
            awaitFrame()

            rig.calls.said.first() shouldBe "twoPane:true"
        }

        test("picking a person opens them and lands focus on the Review heading") {
            val rig = PersonMatchRig()
            val host = rig.mount()
            awaitFrame()

            host.rows().first().click()
            rig.review.value = personReady()
            awaitFrame()
            awaitFrame()

            rig.calls.said.last() shouldBe "pick:P1"
            focusedId() shouldBe REVIEW_HEADING_ID
        }

        test("Back to results returns focus to the row that was open") {
            val chosen = person(isBest = true)
            val rig = PersonMatchRig(find = authorResults(pickedKey = chosen.key), review = personReady(candidate = chosen))
            val host = rig.mount()
            awaitFrame()

            host.buttonNamed("Back to results").shouldNotBeNull().click()
            rig.review.value = PersonReviewUiState.NoneChosen
            awaitFrame()
            awaitFrame()

            rig.calls.said.last() shouldBe "back"
            focusedId() shouldBe rowIdOf(chosen.id)
        }

        test("Skip to Apply comes straight after the Review heading and lands on Apply") {
            val host = PersonMatchRig(review = personReady()).mount()
            awaitFrame()

            val heading = host.querySelector("#$REVIEW_HEADING_ID") as HTMLElement
            val skip = heading.nextElementSibling as HTMLElement
            skip.textContent?.trim() shouldBe "Skip to Apply"
            skip.click()
            awaitFrame()

            focusedId() shouldBe APPLY_ID
        }

        // MARK: Review

        test("Review's header names the person, their role and where they came from") {
            val host = PersonMatchRig(review = personReady()).mount()
            awaitFrame()

            host.text(".bmx-head-t") shouldBe "Andy Weir"
            host.text(".bmx-head .pmx-from") shouldBe "Author · from Audible and Hardcover"
            host.text(".bmx-head .pmx-lib-line") shouldBe "Wrote 3 books in your library"
            host.text(".pmx-apart") shouldBe "Photo and biography, chosen separately."
            host.texts(".bmx-sec-t") shouldContainExactly listOf("Photo", "Biography")
        }

        test("the photo: a Change photo checkbox, and one radio group with Keep current first") {
            val host = PersonMatchRig(review = personReady()).mount()
            awaitFrame()

            host.inputNamed("Change photo").shouldNotBeNull().checked shouldBe true
            val fieldset = host.querySelector(".pmx-photos").shouldNotBeNull()
            fieldset.querySelector("legend")?.textContent?.trim() shouldBe "Photo"
            val radios = fieldset.querySelectorAll("input[type=radio]").asList().filterIsInstance<HTMLInputElement>()
            radios.map { it.getAttribute("aria-label") } shouldContainExactly
                listOf("Keep current photo", "Photo from Audible", "Photo from Hardcover")
            radios.map { it.checked } shouldContainExactly listOf(false, true, false)
            host.texts(".pmx-photos .bmx-dim").first() shouldBe "No photo yet"
        }

        test("the biography: an Apply biography checkbox, the source switch with Keep yours, Yours and Proposed") {
            val host = PersonMatchRig(review = personReady()).mount()
            awaitFrame()

            host.inputNamed("Apply biography").shouldNotBeNull().checked shouldBe true
            val switch = host.querySelector(".pmx-bio-src").shouldNotBeNull()
            switch.querySelector("legend")?.textContent?.trim() shouldBe "Biography source"
            switch.texts(".bmx-seg") shouldContainExactly listOf("Audible", "Hardcover", "Keep yours")
            host.texts(".pmx-bio .bmx-val dt") shouldContainExactly listOf("Yours", "Proposed · from Audible")
            host.texts(".pmx-bio .bmx-val dd").first() shouldBe "A writer."
            host.buttonNamed("Read all").shouldNotBeNull()
        }

        test("with no biography of yours there is nothing to keep, so no Keep yours") {
            val host =
                PersonMatchRig(review = personReady(biography = biography(state = FieldState.FILLS_GAP, current = null)))
                    .mount()
            awaitFrame()

            host.querySelector(".pmx-bio-src")!!.texts(".bmx-seg") shouldContainExactly listOf("Audible", "Hardcover")
            host.texts(".pmx-bio .bmx-val dd").first() shouldBe "—"
        }

        test("photo and biography are ticked and chosen apart") {
            val rig = PersonMatchRig(review = personReady())
            val host = rig.mount()
            awaitFrame()

            host.inputNamed("Change photo")!!.click()
            host.inputNamed("Photo from Hardcover")!!.click()
            host.inputNamed("Keep current photo")!!.click()
            host.inputNamed("Apply biography")!!.click()
            host.querySelector(".pmx-bio-src")!!.inputNamed("Hardcover")!!.click()
            host.querySelector(".pmx-bio-src")!!.inputNamed("Keep yours")!!.click()
            awaitFrame()

            rig.calls.said.drop(1) shouldContainExactly
                listOf("photoTicked:false", "photo:ph-h", "photo:keep", "bioTicked:false", "bio:bio-h", "bio:keep")
        }

        test("a biography you edited starts unticked and says who edited it") {
            val edited =
                biography(
                    state = FieldState.USER_EDITED,
                    choice = FieldChoice.KeepCurrent,
                    handEdit = HandEdit(byUserId = "u-me", byName = "Simon", at = SEP_12),
                )
            val host = PersonMatchRig(review = personReady(biography = edited)).mount()
            awaitFrame()

            host.inputNamed("Apply biography")!!.checked shouldBe false
            host.text(".pmx-bio .bmx-edited") shouldBe "You edited this"
            host.text(".pmx-bio .bmx-edited-note") shouldBe "Edited by you, 12 Sep. Kept unless you tick it."
        }

        test("a photo set by hand says so and keeps it") {
            val byHand =
                photo(
                    currentPath = "contributors/c-1.jpg",
                    setByHand = true,
                    state = FieldState.USER_EDITED,
                    choice = ImageChoice.KeepCurrent,
                )
            val host = PersonMatchRig(review = personReady(photo = byHand)).mount()
            awaitFrame()

            host.inputNamed("Change photo")!!.checked shouldBe false
            host.inputNamed("Keep current photo")!!.checked shouldBe true
            host.text(".pmx-photo .bmx-edited-note") shouldBe
                "You set this photo by hand. Kept unless you choose another."
            host.texts(".pmx-photos .bmx-dim").first() shouldBe "Your photo"
            (host.querySelector(".pmx-photos img") as HTMLElement).getAttribute("src") shouldBe
                "/api/v1/contributors/c-1/photo"
        }

        test("a biography that already matches says so, with nothing to tick") {
            val same = biography(state = FieldState.SAME, current = "A writer.", choice = FieldChoice.KeepCurrent)
            val host = PersonMatchRig(review = personReady(biography = same)).mount()
            awaitFrame()

            host.inputNamed("Apply biography").shouldBeNull()
            host.text(".pmx-bio .bmx-note") shouldBe "Your biography already matches."
        }

        test("the Apply bar says what Apply will write, and nothing selected disables it") {
            val rig = PersonMatchRig(review = personReady())
            val host = rig.mount()
            awaitFrame()

            host.text(".bmx-bar-sum") shouldBe "Photo · biography"
            host.text(".bmx-bar .bmx-note") shouldBe "Nothing changes until you apply."

            rig.review.value = personReady(biography = biography(choice = FieldChoice.KeepCurrent))
            awaitFrame()
            host.text(".bmx-bar-sum") shouldBe "Photo"

            rig.review.value =
                personReady(photo = photo(choice = ImageChoice.KeepCurrent), biography = biography(choice = FieldChoice.KeepCurrent))
            awaitFrame()
            host.text(".bmx-bar-sum") shouldBe "Nothing selected"
            val apply = host.querySelector("#$APPLY_ID") as HTMLElement
            apply.getAttribute("aria-disabled") shouldBe "true"
            apply.click()
            awaitFrame()

            rig.calls.said.contains("apply") shouldBe false
        }

        test("Apply applies, says Applying… while it runs, and a failure says Nothing was changed") {
            val rig = PersonMatchRig(review = personReady())
            val host = rig.mount()
            awaitFrame()

            (host.querySelector("#$APPLY_ID") as HTMLElement).click()
            rig.calls.said.last() shouldBe "apply"

            rig.review.value = personReady(applying = true)
            awaitFrame()
            awaitFrame()
            host.text("#$APPLY_ID") shouldBe "Applying…"
            host.live() shouldBe "Applying…"

            rig.review.value = personReady(applyError = MetadataError.CoverDownloadFailed())
            awaitFrame()
            host.text(".bmx-apply .bmx-err").shouldNotBeNull() shouldContain "Nothing was changed."
        }

        test("Applied hands back to the contributor page") {
            val rig = PersonMatchRig(review = personReady())
            rig.mount()
            awaitFrame()

            rig.events.send(PersonMatchEvent.Applied(MatchReceipt("r-p", 0, emptyList(), undoable = true)))
            awaitFrame()
            awaitFrame()

            rig.applied shouldBe 1
        }

        test("a Review that reloaded says the person changed") {
            val rig = PersonMatchRig(review = personReady())
            val host = rig.mount()
            awaitFrame()

            rig.events.send(PersonMatchEvent.ReviewReloaded)
            awaitFrame()
            awaitFrame()

            host.text(".bmx-review .bmx-err") shouldBe
                "This person changed while you were reviewing. Check the changes again."
            host.live() shouldBe "This person changed while you were reviewing. Check the changes again."
        }

        test("the breadcrumb leads Library › person › Match details, and the person's crumb goes back") {
            val rig = PersonMatchRig()
            val host = rig.mount()
            awaitFrame()

            val crumb = host.querySelector("nav.crumb").shouldNotBeNull()
            crumb.textContent.orEmpty() shouldContain "Library"
            crumb.textContent.orEmpty() shouldContain "Andy Weir"
            crumb.querySelector("[aria-current=page]")?.textContent?.trim() shouldBe "Match details"
            crumb
                .querySelectorAll("a, button")
                .asList()
                .filterIsInstance<HTMLElement>()
                .first { it.textContent?.trim() == "Andy Weir" }
                .click()

            rig.openedContributor shouldBe 1
        }

        test("the narrator's Review header uses their role") {
            val host =
                PersonMatchRig(find = narratorResults(), review = personReady(candidate = RAY, role = ContributorRole.NARRATOR))
                    .mount()
            awaitFrame()

            host.text(".bmx-head .pmx-from") shouldBe "Narrator · from Hardcover"
            host.text(".bmx-head .pmx-lib-line") shouldBe "Narrated 5 books in your library"
        }
    })
