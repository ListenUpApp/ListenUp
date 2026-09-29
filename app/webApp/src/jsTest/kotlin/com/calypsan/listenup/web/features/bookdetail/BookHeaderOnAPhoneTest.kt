package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.contentOverflow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * The book header on a phone: every action reachable, and the title given the width to read.
 *
 * The cover sat beside the title block at every width, so on a 390px phone the title wrapped over
 * four lines in the 166px left beside it, and the action row — Play, queue, Edit, Match — ran 120px
 * past the screen's edge. `.shell-main` scrolls sideways, so the page never overflowed: Edit and Match
 * were simply off screen with nothing to say they existed.
 */
class BookHeaderOnAPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun header(width: Int) =
            frames.mount(width) {
                InShell {
                    BookDetailPage(
                        state = readyBook(),
                        tab = "overview",
                        onSelectTab = {},
                        onOpenLibrary = {},
                        onPlay = {},
                        onEdit = {},
                        onRetryConnection = {},
                    )
                }
            }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px every header action is on screen") {
                val frame = header(width)
                val actions = frame.findAll(".bd-actions > *")
                val edge = frame.rect(frame.find(".bd")).right

                actions.shouldNotBeEmpty()
                actions.forEach { frame.rect(it).right shouldBeLessThanOrEqual edge }
                frame.contentOverflow() shouldBe 0
            }

            test("at ${width}px the title sits under the cover, across the full width") {
                val frame = header(width)
                val cover = frame.rect(frame.find(".bd-head > :first-child"))
                val title = frame.rect(frame.find(".bd-tblock"))

                cover.bottom shouldBeLessThanOrEqual title.top
            }
        }
    })
