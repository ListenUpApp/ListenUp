package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The selection bar on the smallest phone the Library serves: with Select all and an admin's three actions, every
 * control sits inside the bar. One row, shorter labels; the icon carries an action's meaning.
 */
class BulkBarPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("at 360px every control of an admin's Library selection is inside the bar") {
            val frame =
                frames.mount(width = NARROW_PHONE) {
                    BulkBar(
                        count = 248,
                        label = "248 books selected",
                        actions =
                            listOf(
                                BulkAction("Edit", WebIcon.Pencil) {},
                                BulkAction("Add to shelf", WebIcon.Bookmark) {},
                                BulkAction("Add to collection", WebIcon.Layers) {},
                            ),
                        onClear = {},
                        selectAll = BulkAction("Select all 248", shortLabel = "All") {},
                    )
                }
            awaitFrame()

            val bar = frame.find(".bulk")
            val edge = frame.rect(bar).right
            frame
                .findAll(".bulk button")
                .filter { frame.rect(it).right > edge + 0.5 }
                .map { it.getAttribute("aria-label") }
                .shouldBeEmpty()
            (bar.scrollWidth <= bar.clientWidth) shouldBe true
            // The short label is what a phone shows; the full one stays the button's name.
            frame.find(".bulk-all").getAttribute("aria-label") shouldBe "Select all 248"
        }
    })

private const val NARROW_PHONE = 360
