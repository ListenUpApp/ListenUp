package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * Enter in the name dialog (new genre, rename genre, new collection) does what its verb does —
 * once, and never on a blank name. Driven through `requestSubmit()`, the path implicit submission
 * takes (see `EnterSubmitsTest`).
 */
class NameDialogEnterTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun nameDialog(onConfirm: (String) -> Unit): HTMLElement =
            mounts.mount {
                NameDialog(
                    title = "New genre",
                    confirmLabel = "Create",
                    initialName = "",
                    hint = null,
                    onConfirm = onConfirm,
                    onDismiss = {},
                )
            }

        suspend fun typeAndPressEnter(
            host: HTMLElement,
            value: String,
        ) {
            val field = host.querySelector("dialog input") as HTMLInputElement
            field.value = value
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            val form = field.form ?: error("not in a form — Enter cannot submit it")
            form.asDynamic().requestSubmit()
            awaitFrame()
        }

        test("Enter creates with the trimmed name, once") {
            val named = mutableListOf<String>()
            val host = nameDialog { named += it }

            typeAndPressEnter(host, "  Cosy mystery ")

            named shouldBe listOf("Cosy mystery")
        }

        test("Enter on a blank name creates nothing") {
            val named = mutableListOf<String>()
            val host = nameDialog { named += it }

            typeAndPressEnter(host, "   ")

            named shouldBe emptyList()
        }
    })
