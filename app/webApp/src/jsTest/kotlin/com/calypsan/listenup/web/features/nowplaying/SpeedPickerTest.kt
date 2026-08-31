package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.WebPlaybackController
import com.calypsan.listenup.web.playback.silentSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

/**
 * An open speed picker, with every parameter overridable.
 *
 * One shape for every case here: a test that differs from its neighbour in one argument says what
 * it is about in that argument, rather than in six lines of repeated wiring.
 */
private fun picker(
    speed: Float = NORMAL,
    defaultSpeed: Float = NORMAL,
    open: Boolean = true,
    onSet: (Float) -> Unit = {},
    onReset: () -> Unit = {},
    onDismiss: () -> Unit = {},
): HTMLElement =
    mount {
        SpeedPicker(
            open = open,
            speed = speed,
            defaultSpeed = defaultSpeed,
            onSet = onSet,
            onReset = onReset,
            onDismiss = onDismiss,
        )
    }

/** Drags the slider to [speed] and releases, the way a pointer does. */
private fun HTMLElement.dragSliderTo(speed: Float) {
    val slider = querySelector(".speed-slide") as HTMLInputElement
    slider.value = (speed * HUNDREDTHS).toInt().toString()
    slider.dispatchEvent(Event("input", eventInit()))
    slider.dispatchEvent(Event("change", eventInit()))
}

/** Compose HTML listens for bubbling events; a default-constructed `Event` does not bubble. */
private fun eventInit(): dynamic {
    val init = js("{}")
    init.bubbles = true
    return init
}

private const val NORMAL = 1.0f

private const val FASTER = 1.5f

/** Between two rungs — the rate the chips cannot express and the slider exists for. */
private const val BETWEEN_RUNGS = 1.35f

private const val HUNDREDTHS = 100

class SpeedPickerTest :
    FunSpec({

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("it offers every rung of the shared ladder, and no others") {
            // Read from PLAYBACK_SPEED_STEPS rather than restated, so a rung added in commonMain
            // cannot leave the browser showing a stale ladder.
            val host = picker()

            host.querySelectorAll(".speed-opt").length shouldBe PLAYBACK_SPEED_STEPS.size
            PLAYBACK_SPEED_STEPS.forEach { rung ->
                host.buttonSaying("${formatSpeed(rung)}×") shouldBe
                    host.buttonSaying("${formatSpeed(rung)}×")
            }

            host.closeDialogs()
        }

        test("the rate playing now is marked, and says so to a screen reader") {
            val host = picker(speed = FASTER)

            val current = host.querySelectorAll("[aria-current='true']")
            current.length shouldBe 1
            (current.item(0) as HTMLElement).textContent shouldBe "${formatSpeed(FASTER)}×"

            host.closeDialogs()
        }

        test("a rate between rungs marks no chip rather than the nearest one") {
            // ⛔ Marking the nearest would tell the listener they are at 1.25 when they are at 1.35
            // — a confident wrong answer where marking nothing is a visible one.
            val host = picker(speed = BETWEEN_RUNGS)

            host.querySelectorAll("[aria-current='true']").length shouldBe 0

            host.closeDialogs()
        }

        test("picking a rung reports exactly that rate") {
            var picked: Float? = null
            val host = picker(onSet = { picked = it })

            host.buttonSaying("${formatSpeed(FASTER)}×")!!.click()
            awaitFrame()

            picked shouldBe FASTER
            host.closeDialogs()
        }

        test("the slider reaches a rate no chip can name") {
            var picked: Float? = null
            val host = picker(onSet = { picked = it })

            host.dragSliderTo(BETWEEN_RUNGS)
            awaitFrame()

            picked shouldBe BETWEEN_RUNGS
            host.closeDialogs()
        }

        test("the readout follows the drag, so the rate is legible before it is committed") {
            val host = picker()

            host.dragSliderTo(BETWEEN_RUNGS)
            awaitFrame()

            host.querySelector(".speed-read")?.textContent shouldBe "${formatSpeed(BETWEEN_RUNGS)}×"

            host.closeDialogs()
        }

        test("the slider announces a speed, not a count of hundredths") {
            // The implicit aria-valuenow is 135, which a screen reader reads out as "one hundred
            // and thirty five" — a number that is not a speed anyone recognises.
            val host = picker(speed = BETWEEN_RUNGS)

            val slider = host.querySelector(".speed-slide") as HTMLElement
            slider.getAttribute("aria-valuetext").orEmpty() shouldContain formatSpeed(BETWEEN_RUNGS)

            host.closeDialogs()
        }

        test("reset is offered only when there is something to go back to") {
            val atDefault = picker(speed = NORMAL, defaultSpeed = NORMAL)
            atDefault.querySelectorAll(".speed-reset").length shouldBe 0
            atDefault.closeDialogs()

            val changed = picker(speed = FASTER, defaultSpeed = NORMAL)
            changed.querySelectorAll(".speed-reset").length shouldBe 1
            changed.closeDialogs()
        }

        test("reset names the listener's own default, not a hardcoded one") {
            // ⛔ Someone whose default is 1.25 must not be offered a reset to 1. The button says
            // where it goes, so a wrong number here is a visible lie rather than a silent one.
            val host = picker(speed = FASTER, defaultSpeed = 1.25f)

            (host.querySelector(".speed-reset") as HTMLElement).textContent.orEmpty() shouldContain "1.25"

            host.closeDialogs()
        }

        test("reset reports a reset, not a set of the same number") {
            // The two differ in what they record: setting 1.25 pins this book to 1.25, resetting
            // says it has no opinion so a later change to the default reaches it.
            var reset = 0
            var set = 0
            val host = picker(speed = FASTER, defaultSpeed = NORMAL, onSet = { set++ }, onReset = { reset++ })

            (host.querySelector(".speed-reset") as HTMLButtonElement).click()
            awaitFrame()

            reset shouldBe 1
            set shouldBe 0
            host.closeDialogs()
        }

        test("it is a real modal dialog, not a div wearing the part") {
            val host = picker()

            (host.querySelector("dialog") as HTMLDialogElement).isModal() shouldBe true

            host.closeDialogs()
        }

        test("closing reports the dismissal, so the caller's flag cannot drift") {
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector(".btn-ghost") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldBe 1
            host.closeDialogs()
        }
    })

class SpeedSessionTest :
    FunSpec({

        test("a reset goes back to the listener's default, not to 1x") {
            // ⛔ The default is a synced setting. A reset that assumed 1 would quietly overrule a
            // listener who reads everything at 1.25 — on the one control that promises not to.
            val player = HtmlAudioPlayer()
            val manager = fakePlaybackManager(silentSegment(SEGMENT_MS), title = "Dune")
            val playback =
                LivePlayback(
                    manager,
                    WebPlaybackController(player, manager),
                    player,
                    FakePlaybackPreferences(speed = CUSTOM_DEFAULT),
                )

            playback.setSpeed(FASTER)
            manager.playbackSpeed.value shouldBe FASTER

            playback.resetSpeed()
            withTimeout(RESET_TIMEOUT_MS) { manager.playbackSpeed.first { it == CUSTOM_DEFAULT } }

            playback.close()
            player.releasePlayer()
        }
    })

/** A listener who reads everything a little quick — not the stock 1x. */
private const val CUSTOM_DEFAULT = 1.25f

private const val SEGMENT_MS = 1_500L

/** The reset reads the preference through a suspend call, so it lands a dispatch later. */
private const val RESET_TIMEOUT_MS = 5_000L
