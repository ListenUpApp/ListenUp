package com.calypsan.listenup.web.features.seriesedit

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.ExistingSeriesMatch
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import com.calypsan.listenup.client.presentation.seriesedit.PendingSubSeriesMove
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesCandidateUi
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesPlacement
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * "Add sub-series to Cosmere" — the dialog the series page and the editor share.
 *
 * What these pin: each candidate says where it sits now and what choosing it does; a series already
 * here stays listed but can't be chosen, its reason tied to it; a move out of another parent is
 * confirmed in words first; the "New series" form refuses a name that exists and offers that
 * series instead; and a refusal is acknowledged once (the shell's toast already said it).
 */
class SubSeriesDialogsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val events = mutableListOf<AddSubSeriesEvent>()
        beforeTest { events.clear() }

        fun dialogs(state: AddSubSeriesUiState): HTMLElement = mounts.mount { AddSubSeriesDialogs(state) { events += it } }

        fun candidate(
            id: String,
            name: String,
            placement: SubSeriesPlacement,
            parent: String? = null,
            books: Int = 3,
        ) = SubSeriesCandidateUi(id, name, null, books, 0, placement, parent)

        fun open(
            candidates: List<SubSeriesCandidateUi> = emptyList(),
            pendingMove: PendingSubSeriesMove? = null,
            newSeries: NewSeriesDraft? = null,
            query: String = "",
        ) = AddSubSeriesUiState.Open("Cosmere", query, candidates, pendingMove, newSeries, isBusy = false, error = null)

        val list =
            listOf(
                candidate("ws", "White Sand", SubSeriesPlacement.TOP_LEVEL),
                candidate("cw", "City Watch", SubSeriesPlacement.IN_OTHER_PARENT, parent = "Discworld"),
                candidate("mb", "Mistborn", SubSeriesPlacement.ALREADY_HERE, parent = "Cosmere"),
            )

        fun HTMLElement.rows() = querySelectorAll(".sh-row:not(.sh-pinned)").asList().map { it as HTMLElement }

        test("a closed sheet renders nothing") {
            val root = dialogs(AddSubSeriesUiState.Closed())

            root.querySelector("dialog") shouldBe null
        }

        test("each candidate says where it sits and what choosing it does") {
            val root = dialogs(open(list))

            root.rows().map { it.querySelector(".sh-row-m")?.textContent } shouldContainExactly
                listOf("Top level · 3 books", "In Discworld · moves it here", "Already in Cosmere")
        }

        test("a series already here is listed but can't be chosen, its reason tied to it") {
            val root = dialogs(open(list))

            val already = root.rows()[2]
            already.getAttribute("aria-disabled") shouldBe "true"
            val reasonId = already.getAttribute("aria-describedby")
            (root.querySelector("#$reasonId") as HTMLElement).textContent shouldBe "Already in Cosmere"
            already.click()
            events shouldBe emptyList()
        }

        test("choosing a candidate reports it") {
            val root = dialogs(open(list))

            root.rows()[1].click()

            events shouldBe listOf(AddSubSeriesEvent.Chosen("cw"))
        }

        test("typing searches; New series… starts the form") {
            val root = dialogs(open(list))

            (root.querySelector(".sh-pinned") as HTMLElement).click()

            events shouldBe listOf(AddSubSeriesEvent.NewSeriesStarted)
        }

        test("an empty search says nothing matches") {
            val root = dialogs(open(emptyList(), query = "zzz"))

            root.textContent.orEmpty().contains("No series match that search.") shouldBe true
        }

        test("moving a series out of another parent asks first, in words") {
            val root = dialogs(open(list, pendingMove = PendingSubSeriesMove("cw", "City Watch", "Discworld", "Cosmere")))

            (root.querySelector("dialog .dlg-t") as HTMLElement).textContent shouldBe
                "Move City Watch out of Discworld into Cosmere?"
            root
                .querySelectorAll("dialog button")
                .asList()
                .map { it as HTMLElement }
                .first { it.textContent?.trim() == "Move" }
                .click()

            events shouldBe listOf(AddSubSeriesEvent.MoveConfirmed)
        }

        test("the new series form says where it will go, and creates on a valid name") {
            val root = dialogs(open(newSeries = NewSeriesDraft("Secret Projects")))

            (root.querySelector("dialog .dlg-t") as HTMLElement).textContent shouldBe "New series"
            root.textContent.orEmpty().contains("Creates “Secret Projects” inside Cosmere.") shouldBe true
            root
                .querySelectorAll("dialog button")
                .asList()
                .map { it as HTMLButtonElement }
                .first { it.textContent?.trim() == "Create" }
                .click()

            events shouldBe listOf(AddSubSeriesEvent.NewSeriesConfirmed)
        }

        test("a name that already exists can't be created, and that series is offered instead") {
            val draft = NewSeriesDraft("Dune", existing = ExistingSeriesMatch("dune", "Dune", isSelectable = true))
            val root = dialogs(open(newSeries = draft))

            val buttons = root.querySelectorAll("dialog button").asList().map { it as HTMLButtonElement }
            buttons.first { it.textContent?.trim() == "Create" }.disabled shouldBe true
            root.textContent.orEmpty().contains("“Dune” already exists.") shouldBe true
            buttons.first { it.textContent?.trim() == "Add it instead" }.click()

            events shouldBe listOf(AddSubSeriesEvent.Chosen("dune"))
        }

        test("a refusal is acknowledged once — the shell's toast has already said it") {
            dialogs(AddSubSeriesUiState.Closed(error = TransportError.NetworkUnavailable()))
            awaitFrame()

            events shouldBe listOf(AddSubSeriesEvent.ErrorDismissed)
        }
    })
