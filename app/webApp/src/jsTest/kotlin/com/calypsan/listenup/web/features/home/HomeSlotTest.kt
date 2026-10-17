package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement

/** Spec §2.3 Resume and §2.4 on web. */
class HomeSlotTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val now = 1_760_000_000_000L

        fun hero(
            slot: HomeSlot.Resume,
            onPlay: (String) -> Unit = {},
            onOpenBook: (String) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                ResumeHero(slot, isOnlyBook = false, nowMs = now, onOpenBook = onOpenBook, onPlay = onPlay)
            }

        test("Resume calls the play entry with the book") {
            var played: String? = null
            val host = hero(resumeSlot("wok", lastPlayedAtMs = now - 2 * 3_600_000L), onPlay = { played = it })
            (host.querySelector(".home-hero-resume") as HTMLElement).click()
            played shouldBe "wok"
        }

        test("a playable Resume carries no disabled attribute at all") {
            val host = hero(resumeSlot("wok"))
            (host.querySelector(".home-hero-resume") as HTMLElement).hasAttribute("disabled") shouldBe false
        }

        test("a book that cannot stream disables Resume and says why") {
            val host = hero(resumeSlot("wok", canPlay = false))
            (host.querySelector(".home-hero-resume") as HTMLElement).hasAttribute("disabled") shouldBe true
            host.textContent.orEmpty() shouldContain "Can't reach your server, so this book can't stream right now."
        }

        test("the hero says where you are") {
            val host = hero(resumeSlot("wok", lastPlayedAtMs = now - 2 * 3_600_000L))
            val text = host.textContent.orEmpty()
            text shouldContain "Up next · 2 hours ago"
            text shouldContain "Ch. 6 · Bridge Four"
            text shouldContain "11% of 45h 30m"
            text shouldContain "Read by Michael Kramer, Kate Reading"
        }

        test("the cover and title are one control that opens the book; the next book opens itself") {
            val opened = mutableListOf<String>()
            val host = hero(resumeSlot("wok"), onOpenBook = { opened += it })
            (host.querySelector(".home-hero-id") as HTMLElement).click()
            (host.querySelector(".home-hero-next") as HTMLElement).click()
            opened shouldBe listOf("wok", "wor")
            // One tab stop for the identity (§8.2): the cover inside it is decorative.
            host.querySelectorAll(".home-hero-id [tabindex], .home-hero-id button, .home-hero-id a").length shouldBe 0
        }
    })
