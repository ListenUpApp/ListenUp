package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLLabelElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

/**
 * Every labelled control is programmatically tied to its label.
 *
 * Without `for`/`id`, a `<label>` is decoration: a screen reader announces the control as
 * unlabelled (or falls back to the placeholder, which is not a label), and clicking the text does
 * not focus the field. Both were true of every field in this app, which is why association is now
 * generated rather than left to each call site to remember.
 */
class LabelAssociationTest :
    FunSpec({

        fun labelOf(host: HTMLElement): HTMLLabelElement = host.querySelector("label.f-label") as HTMLLabelElement

        fun assertAssociated(host: HTMLElement) {
            val target = labelOf(host).getAttribute("for")
            target.orEmpty().shouldNotBeBlank()
            // The `for` must point at a control that actually exists in the document.
            host.querySelector("#$target") shouldNotBe null
        }

        test("a text field's label points at its input") {
            assertAssociated(mount { Field(label = "Email", value = "", onInput = {}) })
        }

        test("a password field's label points at its input") {
            assertAssociated(mount { PasswordField(label = "Password", value = "", onInput = {}) })
        }

        test("a textarea field's label points at its control") {
            assertAssociated(mount { TextAreaField(label = "Description", value = "", onInput = {}) })
        }

        test("a select field's label points at its control") {
            assertAssociated(
                mount {
                    SelectField(
                        label = "Language",
                        value = null,
                        options = listOf(SelectOption("en", "English")),
                        onSelect = {},
                    )
                },
            )
        }

        test("an explicit id still wins, because specs and deep links address fields by name") {
            val host = mount { Field(label = "Email", value = "", onInput = {}, id = "auth-email") }

            labelOf(host).getAttribute("for") shouldBe "auth-email"
            host.querySelector("#auth-email") shouldNotBe null
        }

        test("two fields on one page never share an id") {
            val host =
                mount {
                    Field(label = "First name", value = "", onInput = {})
                    Field(label = "Last name", value = "", onInput = {})
                }

            val labels = host.querySelectorAll("label.f-label")
            val first = (labels.item(0) as HTMLLabelElement).getAttribute("for")
            val second = (labels.item(1) as HTMLLabelElement).getAttribute("for")

            (first == second) shouldBe false
        }
    })
