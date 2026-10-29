package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import io.kotest.assertions.withClue
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * The phone Library (spec §3.1.7): its count sits beside Filter and sort on one row; the status lives in that menu.
 */
class LibraryPhoneControlsTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun phone() =
            frames.mount(PHONE_390) {
                val books = (1..6).map { contractBook("b$it", "Book number $it") }
                InShell { LibraryPage(state = contractLibrary(books), onEvent = {}, onOpenBook = {}, onSelectFacet = {}) }
            }

        test("'6 books' and 'All · Title, A–Z' share one row, and there is no chip row") {
            val frame = phone()
            awaitFrame()

            val count = frame.find(".lib-count")
            frame.isShown(count) shouldBe true
            count.textContent shouldBe "6 books"
            val sort = frame.find(".lib-controls .lib-sort-trigger")
            val countBox = frame.rect(count)
            val sortBox = frame.rect(sort)
            (countBox.top < sortBox.bottom && sortBox.top < countBox.bottom) shouldBe true
            countBox.right shouldBeLessThan sortBox.left
            frame.findAll(".lib-filters").size shouldBe 0
            // The page header's count line is the desktop's; a phone says it once, here.
            frame.findAll(".lib-header .page-sub").filter(frame::isShown).size shouldBe 0
        }

        suspend fun smallPhone(fonts: Fonts): ViewportFrame {
            val frame =
                frames.mount(SMALL_PHONE) {
                    val books = (1..15).map { contractBook("b$it", "Book number $it") }
                    InShell {
                        LibraryPage(
                            state = contractLibrary(books),
                            onEvent = {},
                            onOpenBook = {},
                            onSelectFacet = {},
                            onStartSelecting = {},
                            inbox = InboxBadgeState(heldCount = 3, previewBookIds = listOf("h1", "h2", "h3")),
                        )
                    }
                }
            when (fonts) {
                Fonts.Loaded -> {
                    frame.awaitFonts()
                }

                Fonts.Fallback -> {
                    frame.withoutWebFonts()
                    awaitFrame()
                }
            }
            return frame
        }

        Fonts.entries.forEach { fonts ->
            test("at 320px ($fonts fonts) the count, Sort and the inbox each keep to their lines, and nothing runs past the edge") {
                val frame = smallPhone(fonts)

                val count = frame.find(".lib-count")
                count.textContent shouldBe "15 books"
                // One line: shorter than two lines of its own type.
                frame.rect(count).height shouldBeLessThan 2 * frame.css(count, "font-size").removeSuffix("px").toDouble()
                val sort = frame.find(".lib-controls .lib-sort-trigger")
                frame.rect(sort).height shouldBeLessThan 48.0
                // "Inbox · 3 new books" and its line, never a five-line column squeezed by the cover fan.
                frame.rect(frame.find(".lib-inbox")).height shouldBeLessThan 96.0
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }
        }

        test("at desktop width the count stays in the page header and the controls row has none") {
            val frame =
                frames.mount(DESKTOP_1280) {
                    val books = (1..6).map { contractBook("b$it", "Book number $it") }
                    InShell { LibraryPage(state = contractLibrary(books), onEvent = {}, onOpenBook = {}, onSelectFacet = {}) }
                }
            awaitFrame()

            frame.isShown(frame.find(".lib-count")) shouldBe false
        }
    })

/** The faces a 320px layout must survive: the web font once it has swapped in, and the fallback before it does. */
private enum class Fonts { Loaded, Fallback }

private const val PHONE_390 = 390
private const val DESKTOP_1280 = 1280
