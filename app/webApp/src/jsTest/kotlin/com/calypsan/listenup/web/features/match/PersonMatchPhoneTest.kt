package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
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

private fun PersonMatchRig.mountIn(
    frames: ViewportFrames,
    width: Int,
    height: Int = 900,
): ViewportFrame = frames.mount(width, height = height) { InShell { Page() } }

/**
 * Person Match details at phone width (W-08): one column, Review replacing Find with a Back link while a person
 * is open, and at 320px with text at 200% nothing scrolls sideways and the Apply bar stays on screen.
 */
class PersonMatchPhoneTest :
    FunSpec({

        val frames = ViewportFrames()
        afterEach { frames.disposeAll() }

        test("a phone tells the session it is one pane, so nothing opens until a tap") {
            val rig = PersonMatchRig()
            rig.mountIn(frames, PHONE)
            awaitFrame()

            rig.calls.said.first() shouldBe "twoPane:false"
        }

        test("on a phone, Review replaces Find, and Back brings Find back with focus on the row that was open") {
            val chosen = person(isBest = true)
            val rig = PersonMatchRig(find = authorResults(pickedKey = chosen.key))
            val frame = rig.mountIn(frames, PHONE)
            awaitFrame()

            frame.isShown(frame.find(".bmx-find")) shouldBe true
            frame.isShown(frame.find(".bmx-review")) shouldBe false

            rig.review.value = personReady(candidate = chosen)
            awaitFrame()
            frame.isShown(frame.find(".bmx-find")) shouldBe false
            frame.isShown(frame.find(".bmx-review")) shouldBe true

            frame.findAll("button").first { it.textContent?.trim() == "Back to results" }.click()
            rig.review.value = PersonReviewUiState.NoneChosen
            awaitFrame()
            awaitFrame()

            frame.host.ownerDocument!!
                .activeElement
                ?.id shouldBe rowIdOf(chosen.id)
        }

        test("at 320px with text at 200%, Review reflows: nothing scrolls sideways and Apply stays on screen") {
            val rig = PersonMatchRig(find = narratorResults(), review = personReady(candidate = RAY))
            val frame = rig.mountIn(frames, SMALL_PHONE)
            frame.zoomTextTo200()
            awaitFrame()

            withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
            val apply = frame.find("#$APPLY_ID")
            frame.rect(apply).right shouldBeLessThanOrEqual frame.rect(frame.find(".shell-main")).right
        }

        test("at 320px with text at 200%, Find reflows too — role switch, coverage note and rows") {
            val rig = PersonMatchRig(find = narratorResults())
            val frame = rig.mountIn(frames, SMALL_PHONE)
            frame.zoomTextTo200()
            awaitFrame()

            withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
        }

        test("the role switch, the photo tiles, the ticks and the source switch are at least 44px") {
            val rig = PersonMatchRig(review = personReady())
            val frame = rig.mountIn(frames, PHONE)
            awaitFrame()

            val targets = frame.findAll(".pmx-role .bmx-seg, .bmx-cover, .bmx-seg, .bmx-field-h .f-check")
            withClue("no targets found") { (targets.size > 0) shouldBe true }
            targets.forEach { target ->
                withClue("${target.className} '${target.textContent?.take(30)}'") {
                    frame.rect(target).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                }
            }
        }
    })
