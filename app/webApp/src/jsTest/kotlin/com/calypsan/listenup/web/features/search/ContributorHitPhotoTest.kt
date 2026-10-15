package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * A contributor found by search is a person, so their row shows them as one: their framed photo in a
 * circle (the board's "List row · circle 48"), or their initials when they have none — the same face
 * the Contributors list and their own page show. Shared by the page, See all and the palette, which
 * all draw [SearchRow].
 */
class ContributorHitPhotoTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun row(hit: com.calypsan.listenup.client.domain.model.SearchHit): HTMLElement =
            mounts.mount { SearchRow(hit = hit, isOpenable = true, onOpen = {}) }

        // Read from the first composition: a spec has no server, so the photo 404s a moment later.
        test("a contributor with a photo is shown by it, framed, in a circle") {
            val host =
                row(
                    contributorHit(
                        id = "c-weir",
                        name = "Andy Weir",
                        imagePath = "contributors/abc.jpg",
                        imageFrame = ImageFrame(0.5, 0.3, 1.5),
                    ),
                )
            val badge = (host.querySelector(".search-badge") as? HTMLElement).shouldNotBeNull()
            badge.classList.contains("is-person") shouldBe true
            val img = (badge.querySelector("img.framed-img") as? HTMLImageElement).shouldNotBeNull()
            img.getAttribute("src") shouldBe "/api/v1/contributors/c-weir/photo?v=contributors/abc.jpg"
        }

        test("a contributor with no photo shows their initials, not a generic person glyph") {
            val host = row(contributorHit(id = "c-x", name = "Ann Leckie"))
            val badge = (host.querySelector(".search-badge") as? HTMLElement).shouldNotBeNull()
            badge.querySelector("img").shouldBeNull()
            badge.querySelector("svg").shouldBeNull()
            badge.textContent shouldBe "AL"
        }

        test("a series keeps its glyph badge: only people have faces") {
            val host = row(seriesHit(id = "s1", name = "The Expanse"))
            val badge = (host.querySelector(".search-badge") as? HTMLElement).shouldNotBeNull()
            badge.classList.contains("is-person") shouldBe false
            badge.querySelector("svg").shouldNotBeNull()
        }
    })
