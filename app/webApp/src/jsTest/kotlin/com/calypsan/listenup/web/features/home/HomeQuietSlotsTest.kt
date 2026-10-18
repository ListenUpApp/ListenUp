package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLElement

private fun HTMLElement.buttonsLabelled(label: String): List<HTMLElement> {
    val buttons = querySelectorAll("button")
    return (0 until buttons.length)
        .map { buttons.item(it) as HTMLElement }
        .filter { it.textContent?.trim() == label }
}

/** Spec §2.3 NothingInProgress and NewListener, and H17, on the web. */
class HomeQuietSlotsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("the candidate plays, and Browse the Library is a second button") {
            var played: String? = null
            var browsed = false
            val host =
                mounts.mount {
                    NothingInProgressSlot(
                        nothingSlot(nextBook("wor")),
                        HomeActions.None.copy(onPlay = { played = it }, onOpenLibrary = { browsed = true }),
                    )
                }
            host.textContent.orEmpty() shouldContain "Nothing in progress"
            host.textContent.orEmpty() shouldContain "Next in a series you're reading"
            host.textContent.orEmpty() shouldContain "Read by Michael Kramer & Kate Reading"
            host.textContent.orEmpty() shouldContain "Stormlight Archive, Book 2 · 48h 15m"
            host.buttonsLabelled("Play").single().click()
            host.buttonsLabelled("Browse the Library").single().click()
            played shouldBe "wor"
            browsed shouldBe true
        }

        test("without a candidate it offers the Library and Discover") {
            var discovered = false
            val host = mounts.mount { NothingInProgressSlot(nothingSlot(null), HomeActions.None.copy(onOpenDiscover = { discovered = true })) }
            host.buttonsLabelled("Play").size shouldBe 0
            host.buttonsLabelled("Browse the Library").size shouldBe 1
            host.buttonsLabelled("Discover").single().click()
            discovered shouldBe true
        }

        test("the welcome invites, counts nothing, and greets by name") {
            val page =
                mounts.mount {
                    HomePage(readyHome(slot = welcomeSlot(4), firstName = "Simon"), HomeStatsUiState.Empty, HomeActions.None)
                }
            (page.querySelector(".page-t") as HTMLElement).textContent shouldBe "Welcome, Simon"
            page.textContent.orEmpty() shouldContain "Pick something new"
            page.textContent.orEmpty() shouldNotContain "waiting for you"
            page.querySelector("[aria-label='Getting started']") shouldNotBe null
            page.buttonsLabelled("Browse the Library").size shouldBe 1
            page.buttonsLabelled("Discover").size shouldBe 1
        }
    })
