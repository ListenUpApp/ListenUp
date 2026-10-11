package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.browser.window
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("cards are the same height whether their titles are short or very long") {
            val root =
                mounts.mount {
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
                        onSelectFacet = {},
                    )
                }

            val cards = root.querySelectorAll(".lib-card")
            val heights = (0 until cards.length).map { (cards.item(it) as HTMLElement).offsetHeight }

            heights shouldHaveSize 3
            heights.toSet().size shouldBe 1
        }

        test("cards are the same height whatever their reading state, narrator or none") {
            // A started card carries coral time left, a finished one a badge on the art, an unstarted
            // one its length, and only one book here has a narrator. None of it may move the row maths.
            val books =
                listOf(
                    contractBook("b1", "Ubik"),
                    contractBook("b2", "Dune").copy(narrators = listOf(BookContributor("n1", "Scott Brick"))),
                    contractBook("b3", "Solaris"),
                )
            val state =
                contractLibrary(books).copy(
                    bookStatus =
                        mapOf(
                            books[0].id to BookCardStatus.InProgress(fraction = 0.5f, timeLeftMs = 1_800_000L),
                            books[1].id to BookCardStatus.Finished(durationMs = 3_600_000L),
                            books[2].id to BookCardStatus.NotStarted(durationMs = 3_600_000L),
                        ),
                )
            // Narrow enough for two columns at most, so each card is wide enough (>= 160px) to show
            // its narrator line — the case where a card with no narrator could come out shorter.
            val root =
                mounts.mount {
                    Div(attrs = { style { width(TWO_UP_WIDTH.px) } }) {
                        LibraryPage(state = state, onEvent = {}, onOpenBook = {}, onSelectFacet = {})
                    }
                }

            val narrator = root.querySelectorAll(".lib-narrator").item(1) as HTMLElement
            (narrator.offsetHeight > 0) shouldBe true
            val cards = root.querySelectorAll(".lib-card")
            val heights = (0 until cards.length).map { (cards.item(it) as HTMLElement).offsetHeight }

            heights shouldHaveSize 3
            heights.toSet().size shouldBe 1
        }

        test("a long title is clamped to one line rather than reserving a second") {
            // The regression this replaces: two reserved lines left a visible gap under every
            // single-line title, which is most of them.
            val root =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(listOf(contractBook("b1", "A Title Long Enough To Wrap If It Were Ever Allowed To"))),
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
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

/** Two 163px columns of the dense grid: wide enough for every card to show its narrator line. */
private const val TWO_UP_WIDTH = 340

/** Rounding slack: `offsetHeight` is a whole number, a computed line-height is not. */
private const val SUB_PIXEL_TOLERANCE = 1.5
