package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.player.stepPlaybackSpeed
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

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

private const val THIRTY = 30

class SpeedPickerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /**
         * An open speed picker, with every parameter overridable.
         *
         * One shape for every case here: a test that differs from its neighbour in one argument
         * says what it is about in that argument, rather than in six lines of repeated wiring.
         */
        fun picker(
            speed: Float = NORMAL,
            defaultSpeed: Float = NORMAL,
            open: Boolean = true,
            leftAtSpeedMs: Long = 0,
            onSet: (Float) -> Unit = {},
            onStep: (Boolean) -> Unit = {},
            onReset: () -> Unit = {},
            onDismiss: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    SpeedPicker(
                        open = open,
                        speed = speed,
                        defaultSpeed = defaultSpeed,
                        leftAtSpeedMs = leftAtSpeedMs,
                        onSet = onSet,
                        onStep = onStep,
                        onReset = onReset,
                        placement = PanelPlacement.Sheet,
                        onDismiss = onDismiss,
                    )
                }
            }

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("it offers every rung of the shared ladder, and no others") {
            // Read from PLAYBACK_SPEED_STEPS rather than restated, so a rung added in commonMain
            // cannot leave the browser showing a stale ladder.
            val host = picker()

            host.querySelectorAll("[role=radio]").length shouldBe PLAYBACK_SPEED_STEPS.size
            PLAYBACK_SPEED_STEPS.forEach { rung -> host.buttonSaying(speedLabel(rung)).shouldNotBeNull() }

            host.closeDialogs()
        }

        test("presets are a radiogroup of radios, the current one checked") {
            val host = picker(speed = 1.25f)

            val group = host.querySelector("[role=radiogroup][aria-label='Speed presets']").shouldNotBeNull()
            group.querySelectorAll("[role=radio]").length shouldBe 9
            val checked = group.querySelectorAll("[role=radio][aria-checked='true']")
            checked.length shouldBe 1
            (checked.item(0) as HTMLElement).textContent shouldBe "1.25×"

            host.closeDialogs()
        }

        test("a rate between rungs marks no chip rather than the nearest one") {
            // ⛔ Marking the nearest would tell the listener they are at 1.25 when they are at 1.35
            // — a confident wrong answer where marking nothing is a visible one.
            val host = picker(speed = BETWEEN_RUNGS)

            host.querySelectorAll("[role=radio][aria-checked='true']").length shouldBe 0
            host.closeDialogs()
            val offRung = picker(speed = 1.3f)
            offRung.querySelectorAll("[role=radio][aria-checked='true']").length shouldBe 0
            offRung.closeDialogs()

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

        test("− and + step by 0.05 through the shared rule") {
            var steps = 0
            val counted = picker(speed = 2.5f, onStep = { up -> if (!up) steps++ })
            val slower = counted.querySelector("button[aria-label='Slower by 0.05']") as HTMLButtonElement
            repeat(THIRTY) { slower.click() }
            steps shouldBe THIRTY
            counted.closeDialogs()

            // Driving the real shared step, thirty presses from 2.5 land exactly on 1.0: no float drift (§11 risk).
            val host =
                mounts.mount {
                    var speed by remember { mutableStateOf(2.5f) }
                    WebAppSurface {
                        SpeedPicker(
                            open = true,
                            speed = speed,
                            defaultSpeed = NORMAL,
                            leftAtSpeedMs = 0,
                            placement = PanelPlacement.Sheet,
                            onSet = { speed = it },
                            onStep = { up -> speed = stepPlaybackSpeed(speed, up) },
                            onReset = {},
                            onDismiss = {},
                        )
                    }
                }
            repeat(THIRTY) {
                (host.querySelector("button[aria-label='Slower by 0.05']") as HTMLButtonElement).click()
                awaitFrame()
            }
            host.querySelector(".speed-read")?.textContent shouldBe "1.0×"
            host.closeDialogs()
        }

        test("the time at this speed is shown") {
            val host = picker(speed = 1.25f, leftAtSpeedMs = 116_640_000)

            host.querySelector(".speed-left")?.textContent shouldBe "32h 24m left at this speed"

            host.closeDialogs()
        }

        test("reset reads 'Reset to 1.0× (your default)', and the footnote says whose speed this is") {
            val host = picker(speed = FASTER, defaultSpeed = NORMAL)

            (host.querySelector(".speed-reset") as HTMLElement).textContent shouldBe "Reset to 1.0× (your default)"
            host.textContent.orEmpty() shouldContain "Applies to this book only."

            host.closeDialogs()
        }

        test("every button is at least 44px tall, and − and + are 44 square") {
            val host = picker(speed = FASTER)

            host.querySelectorAll("dialog button").asList().forEach { button ->
                withClue((button as HTMLElement).textContent.orEmpty()) {
                    button.getBoundingClientRect().height shouldBeGreaterThanOrEqual 44.0
                }
            }
            listOf("Slower by 0.05", "Faster by 0.05").forEach { label ->
                val step = host.querySelector("button[aria-label='$label']") as HTMLElement
                step.getBoundingClientRect().width shouldBeGreaterThanOrEqual 44.0
            }

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

            (host.querySelector(".btn-secondary") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldBe 1
            host.closeDialogs()
        }
    })
