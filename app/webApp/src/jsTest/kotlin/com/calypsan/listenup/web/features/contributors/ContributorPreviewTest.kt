package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A wide people row (spec c7) ends in the contributor's two newest covers; under 1024px it does not. The covers are
 * decoration: the row is named exactly as before.
 */
class ContributorPreviewTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        val ann = contributor("ann", "Ann Leckie", bookCount = 3).copy(previewBookIds = listOf("new", "mid", "old"))

        fun page(width: Int) =
            frames.mount(width) {
                InShell {
                    ContributorsPage(
                        sortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
                        onEvent = {},
                        letters = emptyList(),
                        state = listOf(ann),
                        role = ContributorRole.AUTHOR,
                        onSelectFacet = {},
                        onOpenContributor = {},
                    )
                }
            }

        test("at 1280px a row draws its two newest covers, 40px and hidden from screen readers") {
            val frame = page(1280)
            awaitFrame()

            val strip = frame.find(".contrib-row .contrib-covers")
            frame.isShown(strip) shouldBe true
            strip.getAttribute("aria-hidden") shouldBe "true"
            val covers = frame.findAll(".contrib-covers .cover")
            covers.size shouldBe 2
            covers.forEach { frame.rect(it).width shouldBe 40.0 }
        }

        test("under 1024px there are no covers, and the row reads as it did") {
            val frame = page(900)
            awaitFrame()

            frame.isShown(frame.find(".contrib-row .contrib-covers")) shouldBe false
            frame.find(".contrib-row .contrib-name").textContent shouldBe "Ann Leckie"
        }
    })
