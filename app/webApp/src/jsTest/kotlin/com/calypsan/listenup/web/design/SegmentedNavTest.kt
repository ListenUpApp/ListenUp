package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit

/**
 * The Library's four sections are one segmented control (spec §3.1.6–§3.1.7, board `Library-WebPhone`): a `nav` of
 * real links, the active one `aria-current="page"`, full width with 44px segments on a phone.
 */
class SegmentedNavTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun hrefFor(facet: LibraryFacet) = "/library/${facet.name.lowercase()}"

        test("the sections are a nav named 'Library sections' with four links, the active one aria-current=page") {
            val frame = frames.mount(width = DESKTOP) { FacetRow(active = LibraryFacet.Series, hrefFor = ::hrefFor, onSelect = {}) }
            awaitFrame()

            val nav = frame.find("nav.lib-seg")
            nav.getAttribute("aria-label") shouldBe "Library sections"
            val links = frame.findAll("nav.lib-seg a")
            links.map { it.textContent } shouldBe listOf("Books", "Series", "Authors", "Narrators")
            links.map { it.getAttribute("href") } shouldBe LibraryFacet.entries.map(::hrefFor)
            links.map { it.getAttribute("aria-current") } shouldBe listOf(null, "page", null, null)
        }

        test("every segment is at least 44px tall under 760px, and the control spans the width") {
            val frame = frames.mount(width = PHONE) { FacetRow(active = LibraryFacet.Books, hrefFor = ::hrefFor, onSelect = {}) }
            awaitFrame()

            val nav = frame.find("nav.lib-seg")
            val links = frame.findAll("nav.lib-seg a")
            links.forEach { frame.rect(it).height shouldBeGreaterThanOrEqual 44.0 }
            links.map { frame.rect(it).top }.distinct().size shouldBe 1
            frame.rect(nav).width shouldBeGreaterThanOrEqual PHONE - 2.0 * PHONE_GUTTER - 1.0
        }

        test("a plain click routes in-app; a modified click is left to the browser") {
            val selected = mutableListOf<LibraryFacet>()
            val frame =
                frames.mount(width = DESKTOP) {
                    FacetRow(active = LibraryFacet.Books, hrefFor = ::hrefFor, onSelect = { selected += it })
                }
            awaitFrame()

            val authors = frame.findAll("nav.lib-seg a").first { it.textContent == "Authors" }
            val plain = MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true, button = 0))
            authors.dispatchEvent(plain)
            plain.defaultPrevented shouldBe true
            selected shouldBe listOf(LibraryFacet.Authors)

            val withCtrl = MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true, button = 0, ctrlKey = true))
            withCtrl.preventDefault() // keep the test page where it is; the handler must not claim it
            authors.dispatchEvent(withCtrl)
            selected shouldBe listOf(LibraryFacet.Authors)
        }
    })

private const val DESKTOP = 1280
private const val PHONE = 390
private const val PHONE_GUTTER = 16
