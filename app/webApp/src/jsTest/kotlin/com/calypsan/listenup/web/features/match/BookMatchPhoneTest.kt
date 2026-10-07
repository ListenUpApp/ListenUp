package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MIN_TARGET_PX
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import com.calypsan.listenup.web.zoomTextTo200
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private fun MatchRig.mountIn(
    frames: ViewportFrames,
    width: Int,
    height: Int = 900,
): ViewportFrame =
    frames.mount(width, height = height) {
        InShell {
            BookMatchPage(
                session = session,
                bookId = "b-1",
                viewerId = "u-me",
                view = MatchView.Find,
                onOpenCompare = {},
                onCloseCompare = {},
                onOpenLibrary = {},
                onOpenBook = {},
                onApplied = {},
            )
        }
    }

/**
 * Match details at phone width (W-08): one column, Review replacing Find while a match is open, and
 * at 320px with text at 200% nothing scrolls sideways and the Apply bar wraps rather than escaping.
 */
class BookMatchPhoneTest :
    FunSpec({

        val frames = ViewportFrames()
        afterEach { frames.disposeAll() }

        test("a phone tells the session it is one pane, so nothing opens until a tap") {
            val rig = MatchRig()
            rig.mountIn(frames, PHONE)
            awaitFrame()

            rig.calls.said.first() shouldBe "twoPane:false"
        }

        test("on a phone, Find shows alone until a match is open, then Review replaces it") {
            val rig = MatchRig()
            val frame = rig.mountIn(frames, PHONE)
            awaitFrame()

            frame.isShown(frame.find(".bmx-find")) shouldBe true
            frame.isShown(frame.find(".bmx-review")) shouldBe false

            rig.review.value = ready()
            awaitFrame()

            frame.isShown(frame.find(".bmx-find")) shouldBe false
            frame.isShown(frame.find(".bmx-review")) shouldBe true
        }

        test("Back to results on a phone brings Find back with focus on the row that was open") {
            val chosen = candidate(isBest = true)
            val rig = MatchRig(find = results(pickedKey = chosen.key), review = ready(candidate = chosen))
            val frame = rig.mountIn(frames, PHONE)
            awaitFrame()

            frame.findAll("button").first { it.textContent?.trim() == "Back to results" }.click()
            rig.review.value = ReviewUiState.NoneChosen
            awaitFrame()
            awaitFrame()

            frame.host.ownerDocument!!.activeElement?.id shouldBe rowId(chosen)
        }

        test("at 320px with text at 200%, Review reflows: nothing scrolls sideways and Apply stays on screen") {
            val rig = MatchRig(review = ready())
            val frame = rig.mountIn(frames, SMALL_PHONE)
            frame.zoomTextTo200()
            awaitFrame()

            withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
            val apply = frame.find("#$APPLY_ID")
            frame.rect(apply).right shouldBeLessThanOrEqual frame.rect(frame.find(".shell-main")).right
        }

        test("at 320px, Find reflows too") {
            val rig = MatchRig()
            val frame = rig.mountIn(frames, SMALL_PHONE)
            frame.zoomTextTo200()
            awaitFrame()

            withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
        }

        test("Review's controls are at least 44px: chips, ×, radios, checkboxes and the source switch") {
            val rig = MatchRig(review = ready())
            val frame = rig.mountIn(frames, PHONE)
            awaitFrame()

            val targets =
                frame.findAll(".bmx-chip-x, .bmx-sug, .bmx-cover, .bmx-seg, .bmx-field-h .f-check, .bmx-chapter .f-check")
            withClue("no targets found") { (targets.size > 0) shouldBe true }
            targets.forEach { target ->
                withClue("${target.className} '${target.textContent?.take(30)}'") {
                    frame.rect(target).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                }
            }
            frame.findAll(".bmx-chip-x").forEach { frame.rect(it).width shouldBeGreaterThanOrEqual MIN_TARGET_PX }
        }
    })
