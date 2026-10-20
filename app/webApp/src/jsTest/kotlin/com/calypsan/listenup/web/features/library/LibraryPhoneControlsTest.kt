package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * The phone Library (spec §3.1.7): its count sits beside Sort on one row, and the status chips are one horizontal
 * scroller under them, never a wrapped block.
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

        test("'6 books' and 'Sort: Title, A–Z' share one row") {
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
            // The page header's count line is the desktop's; a phone says it once, here.
            frame.findAll(".lib-header .page-sub").filter(frame::isShown).size shouldBe 0
        }

        test("the chip row is one horizontal scroller") {
            val frame = phone()
            awaitFrame()

            val chips = frame.findAll(".lib-filters .pill")
            chips.map { frame.rect(it).top }.distinct().size shouldBe 1
            frame.css(frame.find(".lib-filters"), "overflow-x") shouldBe "auto"
            frame.css(frame.find(".lib-filters"), "flex-wrap") shouldBe "nowrap"
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

private const val PHONE_390 = 390
private const val DESKTOP_1280 = 1280
