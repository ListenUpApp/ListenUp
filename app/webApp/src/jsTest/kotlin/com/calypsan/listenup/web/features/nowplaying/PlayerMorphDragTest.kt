package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitPopstate
import com.calypsan.listenup.web.motion.forgetPageArrival
import com.calypsan.listenup.web.motion.reducedMotionOverride
import com.calypsan.listenup.web.nav.forgetScrollMemory
import com.calypsan.listenup.web.press
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.delay

/**
 * Dragging the bar up into Now Playing, and Now Playing back down (canvas `GestureWeb`, DRAG and DRAG DOWN TO CLOSE;
 * `GestureAndroid`'s release rule, shared): under 8px a press is a click; past it the top edge follows the finger 1:1,
 * `/playing` is pushed at once so the URL and the motion never disagree, and on release velocity decides first (720
 * px/s), then the halfway line.
 */
class PlayerMorphDragTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest {
            frames.disposeAll()
            reducedMotionOverride = null
            forgetScrollMemory()
            forgetPageArrival()
        }

        /** The bar's top edge: the drag's full travel. Measured where the bar is drawn, not on `/playing`. */
        fun MorphRig.travel(): Double = rect(".tport").top

        /** Opens Now Playing from the bar and lets the morph land; returns the travel, measured first. */
        suspend fun MorphRig.openFully(): Double {
            awaitFrame()
            val travel = travel()
            frame.find(".tport-body").click()
            awaitFrame()
            morphAnimation()?.finish()
            awaitFrame()
            window.location.pathname shouldBe "/playing"
            return travel
        }

        test("a touch that moves under 8px is still a click") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val finger = rig.frame.find(".tport-body").press("touch")
                finger.moveBy(-6.0)
                finger.up()
                awaitFrame()
                window.location.pathname shouldBe "/library"
                rig.isMorphing() shouldBe false
                rig.frame.find(".tport-body").click()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
            } finally {
                rig.dispose()
            }
        }

        test("300px up follows the finger 1:1, with /playing already pushed past the slop") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val travel = rig.travel()
                val finger = rig.frame.find(".tport-body").press("touch")
                finger.moveBy(-20.0)
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                rig.isMorphing() shouldBe true
                finger.moveBy(-280.0, steps = 30, stepMs = 20)
                rig.hasMorphAnimation() shouldBe false
                rig.progress() shouldBe (300.0 / travel plusOrMinus 0.01)
                finger.up()
            } finally {
                rig.dispose()
            }
        }

        test("a slow release past halfway settles on Now Playing") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val travel = rig.travel()
                val finger = rig.frame.find(".tport-body").press("touch")
                finger.moveBy(-20.0)
                awaitFrame()
                finger.moveBy(-(0.6 * travel - 20), steps = 40, stepMs = 20)
                finger.up()
                rig.morphAnimation().finish()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                rig.isMorphing() shouldBe false
                rig.progress() shouldBe 1.0
            } finally {
                rig.dispose()
            }
        }

        test("a slow release short of halfway plays back to the bar and goes back, with focus on the bar's book") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val travel = rig.travel()
                val finger = rig.frame.find(".tport-body").press("touch")
                finger.moveBy(-20.0)
                awaitFrame()
                finger.moveBy(-(0.3 * travel - 20), steps = 20, stepMs = 20)
                finger.up()
                awaitPopstate()
                window.location.pathname shouldBe "/library"
                rig.morphAnimation().finish()
                awaitFrame()
                rig.isMorphing() shouldBe false
                rig.frame.findAll(".np-slot").size shouldBe 0
                rig.frame.host.ownerDocument!!.activeElement shouldBe rig.frame.find(".tport-body")
            } finally {
                rig.dispose()
            }
        }

        test("a fast flick (40px in 30ms) opens from anywhere") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val finger = rig.frame.find(".tport-body").press("touch")
                // Past the slop first, so mounting the page is not inside the flick: a synthetic event's timestamp is
                // when it is dispatched, where a real one's is when the finger moved.
                finger.moveBy(-10.0)
                awaitFrame()
                // Still for longer than the velocity window, so the release's speed is the flick's alone.
                delay(STILL_MS)
                finger.moveBy(-40.0, steps = 3, stepMs = 10)
                finger.up()
                awaitFrame()
                rig.morphAnimation().finish()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                rig.isMorphing() shouldBe false
            } finally {
                rig.dispose()
            }
        }

        test("up 70 %, back down to 30 %, and let go: back to the bar") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val travel = rig.travel()
                val finger = rig.frame.find(".tport-body").press("pen")
                finger.moveBy(-20.0)
                awaitFrame()
                finger.moveBy(-(0.7 * travel - 20), steps = 40, stepMs = 20)
                finger.moveBy(0.4 * travel, steps = 25, stepMs = 20)
                finger.up()
                awaitPopstate()
                rig.morphAnimation().finish()
                awaitFrame()
                window.location.pathname shouldBe "/library"
                rig.isMorphing() shouldBe false
            } finally {
                rig.dispose()
            }
        }

        test("a mouse drags too below 760px") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val finger = rig.frame.find(".tport-body").press("mouse")
                finger.moveBy(-60.0, steps = 4)
                window.location.pathname shouldBe "/playing"
                finger.up()
            } finally {
                rig.dispose()
            }
        }

        test("at 1280px nothing drags") {
            val rig = frames.morphRig("/library", width = 1280, height = 800)
            try {
                awaitFrame()
                val finger = rig.frame.find(".tport-body").press("touch")
                finger.moveBy(-300.0, steps = 10)
                finger.up()
                awaitFrame()
                window.location.pathname shouldBe "/library"
                rig.isMorphing() shouldBe false
            } finally {
                rig.dispose()
            }
        }

        test("down from Now Playing's header, past halfway, closes it") {
            val rig = frames.morphRig("/library")
            try {
                val travel = rig.openFully()
                val finger = rig.frame.find(".np-morph .page-h").press("touch")
                finger.moveBy(20.0)
                awaitFrame()
                rig.isMorphing() shouldBe true
                rig.underPage().isNotEmpty() shouldBe true
                finger.moveBy(0.6 * travel - 20, steps = 40, stepMs = 20)
                finger.up()
                awaitPopstate()
                window.location.pathname shouldBe "/library"
                rig.morphAnimation().finish()
                awaitFrame()
                rig.isMorphing() shouldBe false
                rig.frame.findAll(".np-slot").size shouldBe 0
            } finally {
                rig.dispose()
            }
        }

        test("down a little and let go: Now Playing stays") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                val finger = rig.frame.find(".np-morph .page-h").press("touch")
                finger.moveBy(20.0)
                awaitFrame()
                finger.moveBy(60.0, steps = 10, stepMs = 20)
                finger.up()
                rig.morphAnimation().finish()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                rig.isMorphing() shouldBe false
                rig.underPage().isEmpty() shouldBe true
            } finally {
                rig.dispose()
            }
        }

        test("the scrubber keeps its own drag: progress never moves") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                val finger = rig.frame.find(".np-morph .np-scrub").press("touch")
                finger.moveBy(300.0, steps = 10)
                finger.up()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                rig.isMorphing() shouldBe false
                rig.progress() shouldBe 1.0
            } finally {
                rig.dispose()
            }
        }

        test("the click that follows a drag does not navigate") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                val body = rig.frame.find(".tport-body")
                val finger = body.press("mouse")
                finger.moveBy(-100.0, steps = 10, stepMs = 20)
                finger.up()
                awaitPopstate()
                rig.morphAnimation()?.finish()
                awaitFrame()
                val entries = window.history.length
                body.click()
                awaitFrame()
                window.location.pathname shouldBe "/library"
                window.history.length shouldBe entries
            } finally {
                rig.dispose()
            }
        }
    })

private const val STILL_MS = 150L
