package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val GATE = KeptOffBook("b1", "The Gate of the Feral Gods", "Matt Dinniman", null, null)
private val EDUCATED = KeptOffBook("b2", "Educated", "Tara Westover", null, null)

/** #1541's list on web: the books kept off Hardcover, each with Sync again. */
class KeptOffBooksPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("the page names itself, says what keeping off means, and lists each book with Sync again") {
            val synced = mutableListOf<String>()
            val host =
                mounts.mount {
                    KeptOffBooksPage(
                        state = KeptOffBooksUiState.Loaded(listOf(EDUCATED, GATE)),
                        onSyncAgain = { synced += it },
                        onOpenSettings = {},
                        onOpenHardcover = {},
                    )
                }
            awaitFrame()

            host.querySelector("h1")!!.textContent shouldBe "Kept off Hardcover"
            host.textContent.orEmpty() shouldContain "Nothing about these books is shared with Hardcover or brought in from it."
            host.querySelectorAll(".hc-match-row").length shouldBe 2
            host.querySelector(".hc-count")!!.textContent shouldBe "2"
            host.textContent.orEmpty() shouldContain "Matt Dinniman"
            val syncGate =
                host
                    .querySelectorAll("button")
                    .asList()
                    .map { it as HTMLButtonElement }
                    .single { it.textContent.orEmpty().trim() == "Sync again: The Gate of the Feral Gods" }
            syncGate.firstChild!!.textContent shouldBe "Sync again"
            syncGate.click()
            synced shouldBe listOf("b1")
        }

        test("the breadcrumb leads back to the Hardcover page, and to Settings") {
            var hardcover = 0
            var settings = 0
            val host =
                mounts.mount {
                    KeptOffBooksPage(
                        KeptOffBooksUiState.Loaded(listOf(GATE)),
                        onSyncAgain = {},
                        onOpenSettings = { settings++ },
                        onOpenHardcover = { hardcover++ },
                    )
                }
            awaitFrame()

            val crumbs = host.querySelectorAll(".crumb a").asList().map { it as HTMLElement }
            crumbs.single { it.textContent == "Hardcover" }.click()
            hardcover shouldBe 1
            crumbs.single { it.textContent == "Settings" }.click()
            settings shouldBe 1
            hardcover shouldBe 1
        }

        test("a list the server couldn't give says so and claims no books") {
            val host =
                mounts.mount {
                    KeptOffBooksPage(KeptOffBooksUiState.Unavailable, onSyncAgain = {}, onOpenSettings = {}, onOpenHardcover = {})
                }
            awaitFrame()

            host.textContent.orEmpty() shouldContain "Couldn't load these books. Try again in a moment."
            host.querySelectorAll(".hc-match-row").length shouldBe 0
        }

        test("while loading it claims no books either") {
            val host =
                mounts.mount {
                    KeptOffBooksPage(KeptOffBooksUiState.Loading, onSyncAgain = {}, onOpenSettings = {}, onOpenHardcover = {})
                }
            awaitFrame()

            host.querySelector(".loading[role=status]").shouldNotBeNull()
            host.querySelectorAll(".hc-match-row").length shouldBe 0
        }
    })
