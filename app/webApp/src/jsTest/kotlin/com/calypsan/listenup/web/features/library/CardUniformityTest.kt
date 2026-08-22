package com.calypsan.listenup.web.features.library

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.math.abs

/**
 * Every book card must be exactly the same height, whatever its book is called.
 *
 * This is the load-bearing assumption behind the virtualised grid: row offsets are arithmetic
 * rather than measurement, so a card that is taller than its neighbours drifts the scroll extent
 * and the reader's position with it. Measured before the title and author lines were given fixed
 * heights, cards came out 257 / 274 / 285 depending on how the text wrapped.
 *
 * It is pinned here rather than trusted to a comment because the tempting changes — a second title
 * line, a taller hover state, dropping an empty author row — all look harmless in isolation.
 */
class CardUniformityTest :
    FunSpec({

        test("cards are the same height whether their titles are short or very long") {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) {
                LibraryPage(
                    state =
                        contractLibrary(
                            listOf(
                                contractBook("b1", "Ubik"),
                                contractBook(
                                    "b2",
                                    "The Girl Who Kicked the Hornet's Nest and Then Kept Right On " +
                                        "Kicking Until Every Last Line Of This Title Had Wrapped",
                                ),
                                contractBook("b3", "Dune"),
                            ),
                        ),
                    onEvent = {},
                    onOpenBook = {},
                )
            }

            val cards = root.querySelectorAll(".lib-card")
            val heights = (0 until cards.length).map { (cards.item(it) as HTMLElement).offsetHeight }

            heights shouldHaveSize 3
            heights.toSet().size shouldBe 1
        }

        test("a long title is clamped to one line rather than reserving a second") {
            // The regression this replaces: two reserved lines left a visible gap under every
            // single-line title, which is most of them.
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) {
                LibraryPage(
                    state = contractLibrary(listOf(contractBook("b1", "A Title Long Enough To Wrap If It Were Ever Allowed To"))),
                    onEvent = {},
                    onOpenBook = {},
                )
            }

            val title = root.querySelector(".lib-title") as HTMLElement
            // Compared against the element's OWN computed line-height rather than a hardcoded
            // pixel count, so the assertion survives a change to the root font size.
            val lineHeight =
                window
                    .getComputedStyle(title)
                    .lineHeight
                    .removeSuffix("px")
                    .toDouble()

            abs(title.offsetHeight.toDouble() - lineHeight).shouldBeLessThan(SUB_PIXEL_TOLERANCE)
        }
    })

/** Rounding slack: `offsetHeight` is a whole number, a computed line-height is not. */
private const val SUB_PIXEL_TOLERANCE = 1.5
