package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/** The status strip stays in view while the grid scrolls under it: it explains the whole list, not its first row. */
class LibraryStripPinnedTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("scrolling the grid leaves the arriving strip at the top of the content region") {
            val books = (1..80).map { contractBook("b$it", "Book $it") }
            val state =
                contractLibrary(books).copy(
                    isLibraryArriving = true,
                    statusStrip = LibraryStatusStrip.Arriving(booksSoFar = books.size),
                )
            val frame =
                frames.mount(width = DESKTOP, height = SHORT) {
                    InShell { LibraryPage(state = state, onEvent = {}, onOpenBook = {}, onSelectFacet = {}) }
                }
            awaitFrame()
            val main = frame.find(".shell-main")

            main.scrollTop = SCROLL_PX.toDouble()
            awaitFrame()

            val strip = frame.find(".lib-status")
            frame.rect(strip).top shouldBeGreaterThanOrEqual frame.rect(main).top - 1.0
        }
    })

private const val DESKTOP = 1440
private const val SHORT = 700
private const val SCROLL_PX = 1500
