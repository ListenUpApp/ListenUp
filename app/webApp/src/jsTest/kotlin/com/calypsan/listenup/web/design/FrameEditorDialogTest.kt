package com.calypsan.listenup.web.design

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.ZOOM_STEP
import com.calypsan.listenup.client.core.image.zoomLabel
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

/**
 * The web's "Adjust photo": a modal `<dialog>` over the shared `FrameEditor`, driven by keyboard,
 * pointer and the zoom slider, confirming with Done inside a form and Save from a menu (Q2), and an
 * honest "Couldn't open this photo" when there is nothing to frame.
 */
class FrameEditorDialogTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        class Opened(
            val confirmed: () -> ImageFrame?,
            val cancelled: () -> Boolean,
            val choseAnother: () -> Boolean,
        )

        suspend fun open(
            confirm: FrameConfirm = FrameConfirm.Done,
            photo: DecodedPhoto? = null,
            initialFrame: ImageFrame? = null,
        ): Opened {
            var confirmed: ImageFrame? = null
            var cancelled = false
            var choseAnother = false
            val shown = photo ?: DecodedPhoto.Ready(url = portraitPngUrl(600, 800), width = 600, height = 800)
            mounts.mount {
                FrameEditorDialog(
                    photo = shown,
                    initialFrame = initialFrame,
                    shape = FrameShape.Circle,
                    confirm = confirm,
                    onConfirm = { confirmed = it },
                    onCancel = { cancelled = true },
                    onChooseAnother = { choseAnother = true },
                )
            }
            awaitFrame()
            return Opened({ confirmed }, { cancelled }, { choseAnother })
        }

        fun stage(): HTMLElement = document.querySelector(".fed-stage") as HTMLElement

        fun HTMLElement.key(
            key: String,
            shift: Boolean = false,
        ) {
            dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, shiftKey = shift, bubbles = true, cancelable = true)))
        }

        fun HTMLElement.pointer(
            type: String,
            x: Int,
            y: Int,
            id: Int = 1,
        ) {
            dispatchEvent(PointerEvent(type, PointerEventInit(pointerId = id, clientX = x, clientY = y, bubbles = true)))
        }

        test("it is a modal dialog, labelled Adjust photo") {
            open()
            val dialog = (document.querySelector("dialog.fed") as? HTMLDialogElement).shouldNotBeNull()
            dialog.open shouldBe true
            document.getElementById(dialog.getAttribute("aria-labelledby")!!)!!.textContent shouldBe "Adjust photo"
        }

        test("the stage is a focusable group named Photo position, described by the key hint") {
            open()
            stage().getAttribute("tabindex") shouldBe "0"
            stage().getAttribute("role") shouldBe "group"
            stage().getAttribute("aria-label") shouldBe "Photo position"
            document.getElementById(stage().getAttribute("aria-describedby")!!)!!.textContent!! shouldContain "Arrow keys move"
        }

        test("ArrowUp three times then + then Done stages a frame showing more of the top, zoomed in") {
            val opened = open()
            repeat(3) { stage().key("ArrowUp") }
            stage().key("+")
            awaitFrame()
            buttonLabelled("Done")!!.click()

            val frame = opened.confirmed().shouldNotBeNull()
            frame.focusY shouldBeLessThan 0.5
            frame.zoom shouldBeGreaterThan 1.0
        }

        test("0 resets to the centre crop") {
            val opened = open(initialFrame = ImageFrame(0.5, 0.3, 2.0))
            stage().key("0")
            awaitFrame()
            buttonLabelled("Done")!!.click()
            opened.confirmed() shouldBe ImageFrame.Centered
        }

        test("a pointer drag moves the photo with the pointer") {
            val opened = open()
            stage().pointer("pointerdown", 200, 200)
            stage().pointer("pointermove", 200, 320)
            stage().pointer("pointerup", 200, 320)
            awaitFrame()
            buttonLabelled("Done")!!.click()
            opened.confirmed().shouldNotBeNull().focusY shouldBeLessThan 0.5
        }

        test("two pointers spreading apart zoom in") {
            val opened = open()
            stage().pointer("pointerdown", 200, 200, id = 1)
            stage().pointer("pointerdown", 260, 200, id = 2)
            stage().pointer("pointermove", 300, 200, id = 2)
            awaitFrame()
            buttonLabelled("Done")!!.click()
            opened.confirmed().shouldNotBeNull().zoom shouldBeGreaterThan 1.0
        }

        test("the slider zooms, and says the zoom beside it") {
            val opened = open()
            val range = document.querySelector(".fed input[type=range]") as HTMLInputElement
            range.value = "500"
            range.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            document.querySelector(".fed-zoom-value")!!.textContent shouldBe "2.0×"
            buttonLabelled("Done")!!.click()
            opened.confirmed().shouldNotBeNull().zoom shouldBe (2.0 plusOrMinus 0.01)
        }

        test("Escape (the dialog closing) cancels") {
            val opened = open()
            (document.querySelector("dialog.fed") as HTMLDialogElement).close()
            awaitFrame()
            opened.cancelled() shouldBe true
            opened.confirmed() shouldBe null
        }

        test("inside a form the confirm says Done; from a menu it says Save") {
            open(confirm = FrameConfirm.Save)
            buttonLabelled("Save").shouldNotBeNull()
            buttonLabelled("Done") shouldBe null
        }

        test("Could not open is an alert, offers Choose another, and the confirm is disabled") {
            val opened = open(photo = DecodedPhoto.CouldNotOpen)
            document.querySelector(".fed [role=alert]")!!.textContent!! shouldContain "Couldn't open this photo"
            // Every copy of it: the phone's header confirm and the foot's alike.
            val confirms =
                document.querySelectorAll(".fed button").asList().filterIsInstance<HTMLButtonElement>().filter {
                    it.textContent?.trim() == "Done"
                }
            confirms.size shouldBe 2
            confirms.all { it.disabled } shouldBe true
            buttonLabelled("Choose another")!!.click()
            opened.choseAnother() shouldBe true
        }

        test("the zoom slider is labelled, and the zoom is announced politely") {
            open()
            val range = document.querySelector(".fed input[type=range]") as HTMLInputElement
            document.querySelector("label[for='${range.id}']")!!.textContent shouldBe "Zoom"
            document.querySelector(".fed [aria-live=polite]").shouldNotBeNull()
            stage().key("+")
            awaitFrame()
            document.querySelector(".fed [aria-live=polite]")!!.textContent shouldBe "Zoom ${zoomLabel(ZOOM_STEP)}"
        }
    })
