package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitPopstate
import com.calypsan.listenup.web.motion.forgetPageArrival
import com.calypsan.listenup.web.motion.reducedMotionOverride
import com.calypsan.listenup.web.nav.forgetScrollMemory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/**
 * Closing Now Playing back into the bar (canvas `GestureWeb`, CLICK, P, EXPAND; `Edges`): Collapse, Esc, P and
 * browser Back go back in history first, and the page is held as a leaving layer over the destination while the
 * morph plays in reverse; then it goes, and focus is on the bar's book. A deep link still closes Home, with no reverse.
 */
class PlayerMorphCloseTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest {
            frames.disposeAll()
            reducedMotionOverride = null
            forgetScrollMemory()
            forgetPageArrival()
        }

        /** Opens Now Playing from the bar and lets the morph land. */
        suspend fun MorphRig.openFully() {
            awaitFrame()
            frame.find(".tport-body").click()
            awaitFrame()
            morphAnimation()?.finish()
            awaitFrame()
            window.location.pathname shouldBe "/playing"
            isMorphing() shouldBe false
        }

        /** The reverse is running over the destination; finishing it leaves the bar, with focus on its book. */
        suspend fun MorphRig.assertReverseThenFocus() {
            window.location.pathname shouldBe "/library"
            isMorphing() shouldBe true
            frame.findAll(".np-layer .np-morph").size shouldBe 1
            underPage().isNotEmpty() shouldBe true
            val reverse = morphAnimation()
            reverse.pause()
            reverse.currentTime = 0
            progress() shouldBe (1.0 plusOrMinus 0.001)
            reverse.finish()
            awaitFrame()
            frame.findAll(".np-slot").size shouldBe 0
            isMorphing() shouldBe false
            frame.host.ownerDocument!!.activeElement shouldBe frame.find(".tport-body")
        }

        test("Collapse goes back at once, holds the page as a leaving layer, plays the reverse, then removes it") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                rig.frame.find(".np-morph [aria-label='Collapse player']").click()
                awaitPopstate()
                rig.assertReverseThenFocus()
            } finally {
                rig.dispose()
            }
        }

        test("Esc does the same") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                document.body!!.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
                awaitPopstate()
                rig.assertReverseThenFocus()
            } finally {
                rig.dispose()
            }
        }

        test("P does the same") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                document.body!!.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "p", bubbles = true)))
                awaitPopstate()
                rig.assertReverseThenFocus()
            } finally {
                rig.dispose()
            }
        }

        test("browser Back from /playing plays the reverse") {
            val rig = frames.morphRig("/library")
            try {
                rig.openFully()
                window.history.back()
                awaitPopstate()
                rig.assertReverseThenFocus()
            } finally {
                rig.dispose()
            }
        }

        test("Back while the bar is still growing turns the morph round, from where it is") {
            val rig = frames.morphRig("/library")
            try {
                awaitFrame()
                rig.frame.find(".tport-body").click()
                awaitFrame()
                val open = rig.morphAnimation()
                open.pause()
                open.currentTime = 120
                val midway = rig.progress()
                window.history.back()
                awaitPopstate()
                window.location.pathname shouldBe "/library"
                val reverse = rig.morphAnimation()
                reverse.pause()
                reverse.currentTime = 0
                rig.progress() shouldBe (midway plusOrMinus 0.01)
                reverse.finish()
                awaitFrame()
                rig.isMorphing() shouldBe false
                rig.frame.findAll(".np-slot").size shouldBe 0
            } finally {
                rig.dispose()
            }
        }

        test("closing after a deep link goes Home, with no reverse") {
            val rig = frames.morphRig("/playing")
            try {
                awaitFrame()
                rig.frame.find(".np-morph [aria-label='Collapse player']").click()
                awaitFrame()
                window.location.pathname shouldBe "/"
                rig.isMorphing() shouldBe false
                rig.hasMorphAnimation() shouldBe false
            } finally {
                rig.dispose()
            }
        }

        test("Close book empties the player while closing: no redirect bounce") {
            val state = MutableStateFlow<PlayerUiState>(loadedPlayerState())
            val rig = frames.morphRig("/library", playback = switchablePlayback(state))
            try {
                rig.openFully()
                rig.frame.find(".np-morph [aria-label='Collapse player']").click()
                state.value = PlayerUiState.NothingLoaded
                awaitPopstate()
                rig.morphAnimation()?.finish()
                awaitFrame()
                delay(SETTLE_MS)
                awaitFrame()
                window.location.pathname shouldBe "/library"
                rig.isMorphing() shouldBe false
                rig.frame.findAll(".np-slot").size shouldBe 0
            } finally {
                rig.dispose()
            }
        }
    })

private const val SETTLE_MS = 100L
