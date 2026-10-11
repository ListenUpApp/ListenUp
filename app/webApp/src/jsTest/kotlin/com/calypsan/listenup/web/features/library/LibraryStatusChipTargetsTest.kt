package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MIN_TARGET_PX
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Drive 5a, bug 2: the status chips on a phone are real buttons, and each takes a tap anywhere within
 * 22px of its centre. The scrolling chip row once clipped the chips' shared hit extender to 36px.
 */
class LibraryStatusChipTargetsTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("on a phone each status chip is a button that takes a tap within 22px of its centre") {
            val books = (1..6).map { contractBook("b$it", "Book number $it") }
            val frame =
                frames.mount(PHONE, pointer = Pointer.Touch) {
                    InShell { LibraryPage(state = contractLibrary(books), onEvent = {}, onOpenBook = {}, onSelectFacet = {}) }
                }
            awaitFrame()

            val chips = frame.findAll(".lib-filters .pill")
            chips.size shouldBe 4
            chips.forEach { chip ->
                withClue(chip.textContent) {
                    chip.tagName shouldBe "BUTTON"
                    chip.getAttribute("type") shouldBe "button"
                    frame.takesTapsWithin(chip, MIN_TARGET_PX / 2 - 1) shouldBe true
                }
            }
        }
    })
