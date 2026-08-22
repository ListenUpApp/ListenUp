package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * The focus contract of the field system: ONE ring.
 *
 * `.f-box:focus-within` is the field's focus affordance (coral border + soft halo). The kit-wide
 * `.luw :focus-visible` outline is for controls with no box of their own; on the text control it
 * stacked a second ring inside the box's — the double highlight. These specs pin the split: the
 * box advertises focus, the input itself must not. Only `.f-input` opts out — the eye button in
 * a password field keeps the generic ring, because the box ring alone cannot show WHICH control
 * inside the box holds focus.
 */
class FieldFocusTest :
    FunSpec({

        fun renderedField(): HTMLElement {
            val root = document.createElement("div") as HTMLElement
            // The kit-wide focus rules are scoped to the app root's `luw` class; without it
            // neither the bug nor the fix applies and these specs would pass vacuously.
            root.className = "luw"
            document.body?.appendChild(root)
            renderComposable(root = root) {
                Field(label = "Email", value = "", onInput = {}, id = "focus-probe")
            }
            return root
        }

        test("a focused field draws its ring on the box, not the input") {
            val root = renderedField()
            val input = root.querySelector("#focus-probe") as HTMLInputElement

            input.focus()

            window.getComputedStyle(input).outlineStyle shouldBe "none"
        }

        test("the box still advertises focus once the input's own outline is gone") {
            val root = renderedField()
            val input = root.querySelector("#focus-probe") as HTMLInputElement

            input.focus()

            val box = root.querySelector(".f-box") as HTMLElement
            window.getComputedStyle(box).boxShadow shouldNotBe "none"
        }
    })
