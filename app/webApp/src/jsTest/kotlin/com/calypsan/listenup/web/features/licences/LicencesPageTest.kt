package com.calypsan.listenup.web.features.licences

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit

/**
 * The open-source acknowledgements page.
 *
 * What these pin: the header counts what is actually listed rather than a number of its own, a
 * library with no version renders no version rather than a placeholder, search narrows by both
 * project and licence family, every project link is safe to open, and a manifest that failed to
 * load says so instead of claiming the client uses nothing.
 */
class LicencesPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun licencesPage(
            state: LicencesUiState,
            onOpenSettings: () -> Unit = {},
        ): HTMLElement = mounts.mount { LicencesPage(state = state, onOpenSettings = onOpenSettings) }

        test("the header counts the libraries and their distinct licence families") {
            val root =
                licencesPage(
                    ready(
                        library("io.ktor:ktor-client-core", "Ktor", licences = listOf("Apache-2.0")),
                        library("npm:hls.js", "hls.js", licences = listOf("Apache-2.0")),
                        library("npm:ws", "ws", licences = listOf("MIT")),
                    ),
                )

            (root.querySelector(".lic-title") as HTMLElement).textContent shouldBe "3 libraries"
            (root.querySelector(".lic-sub") as HTMLElement).textContent shouldContain "2 license families"
        }

        test("a library carries its version, and one without renders none rather than a placeholder") {
            val root =
                licencesPage(
                    ready(
                        library("io.ktor:ktor-client-core", "Ktor", version = "3.5.0"),
                        library("npm:ws", "ws", version = null),
                    ),
                )

            root.querySelectorAll(".lic-ver").asList().map { it.textContent } shouldContainExactly listOf("v3.5.0")
        }

        test("search narrows by project name") {
            val root =
                licencesPage(
                    ready(library("io.ktor:ktor-client-core", "Ktor"), library("npm:hls.js", "hls.js")),
                )

            typeSearch(root, "hls")
            awaitFrame()

            root.querySelectorAll(".lic-name").asList().map { it.textContent } shouldContainExactly listOf("hls.js")
        }

        // A reader checking "what is under GPL here?" is asking about the family, not a project.
        test("search also narrows by licence family") {
            val root =
                licencesPage(
                    ready(
                        library("io.ktor:ktor-client-core", "Ktor", licences = listOf("Apache-2.0")),
                        library("npm:ws", "ws", licences = listOf("MIT")),
                    ),
                )

            typeSearch(root, "MIT")
            awaitFrame()

            root.querySelectorAll(".lic-name").asList().map { it.textContent } shouldContainExactly listOf("ws")
        }

        test("a search that matches nothing says so rather than showing an empty frame") {
            val root = licencesPage(ready(library("io.ktor:ktor-client-core", "Ktor")))

            typeSearch(root, "nothing-matches-this")
            awaitFrame()

            (root.querySelector(".empty p") as HTMLElement).textContent shouldBe "No libraries match that."
            root.querySelector(".lic-list") shouldBe null
        }

        // ⛔ Every one of these opens a third-party site in a new tab. Without `noopener` that tab
        // gets a handle on this one via `window.opener`.
        test("every project link opens safely") {
            val root =
                licencesPage(ready(library("npm:hls.js", "hls.js", website = "https://github.com/video-dev/hls.js")))

            val link = root.querySelector(".lic-link") as HTMLElement
            link.getAttribute("target") shouldBe "_blank"
            link.getAttribute("rel") shouldBe "noopener noreferrer"
        }

        test("a library with no website offers no link rather than a dead one") {
            val root = licencesPage(ready(library("npm:ws", "ws", website = null)))

            root.querySelector(".lic-link") shouldBe null
        }

        // ⛔ The one lie this page cannot afford. "No libraries" and "the file did not load" are
        // different facts, and an attribution page must never claim the first when the second is true.
        test("a manifest that failed to load says so rather than listing nothing") {
            val root = licencesPage(LicencesUiState.Error("The licence list could not be loaded."))

            (root.querySelector(".empty h3") as HTMLElement).textContent shouldBe "The licences can't be shown"
            root.querySelector(".lic-list") shouldBe null
            root.querySelector(".lic-title") shouldBe null
        }

        test("loading says so and still offers the way back") {
            val root = licencesPage(LicencesUiState.Loading)

            (root.querySelector(".empty p") as HTMLElement).textContent shouldBe "Loading…"
            root.querySelector(".crumb") shouldNotBe null
        }

        test("the breadcrumb goes back to Settings") {
            var back = 0
            val root = licencesPage(ready(library("npm:ws", "ws")), onOpenSettings = { back++ })

            (root.querySelector(".crumb a") as HTMLElement).click()

            back shouldBe 1
        }
    })

private fun typeSearch(
    root: HTMLElement,
    text: String,
) {
    val input = root.querySelector("#lic-search") as HTMLInputElement
    input.value = text
    input.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

private fun ready(vararg libraries: LicencedLibrary) = LicencesUiState.Ready(libraries.toList())

private fun library(
    uniqueId: String,
    name: String,
    version: String? = "1.0.0",
    website: String? = null,
    licences: List<String> = listOf("Apache-2.0"),
) = LicencedLibrary(
    uniqueId = uniqueId,
    name = name,
    artifactVersion = version,
    description = null,
    website = website,
    licenses = licences,
)
