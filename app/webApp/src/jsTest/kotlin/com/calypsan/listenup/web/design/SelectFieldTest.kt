package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

private fun HTMLElement.optionLabels(): List<String> {
    val options = querySelectorAll("select option")
    return (0 until options.length).map { index -> options.item(index)?.textContent.orEmpty() }
}

private val THEMES =
    listOf(
        SelectOption("SYSTEM", "Match my system"),
        SelectOption("LIGHT", "Light"),
        SelectOption("DARK", "Dark"),
    )

class SelectFieldTest :
    FunSpec({

        test("a picker draws its own chevron, because appearance:none took the browser's away") {
            val host = mount { SelectField(label = "Theme", value = "LIGHT", options = THEMES, onSelect = {}) }

            host.querySelector(".f-box-select .f-caret").shouldNotBeNull()
        }

        test("a picker with a value offers only the real choices") {
            val host = mount { SelectField(label = "Theme", value = "LIGHT", options = THEMES, onSelect = {}) }

            // No fourth "—" option: a theme is always one of three, and offering unset invites a
            // reader to pick a state the screen cannot be in.
            host.optionLabels() shouldContainExactly listOf("Match my system", "Light", "Dark")
        }

        test("a picker names the unset case when the caller says unset is real") {
            val host =
                mount {
                    SelectField(
                        label = "Language",
                        value = null,
                        options = listOf(SelectOption("en", "English")),
                        onSelect = {},
                        emptyLabel = "Not recorded",
                    )
                }

            host.optionLabels() shouldContainExactly listOf("Not recorded", "English")
        }

        test("an unset value still shows a placeholder rather than posing as the first choice") {
            // The caller named no empty label, but the value is null — showing "Match my system"
            // as though it were stored would be the select lying about what it holds.
            val host = mount { SelectField(label = "Theme", value = null, options = THEMES, onSelect = {}) }

            host.optionLabels() shouldContainExactly listOf("—", "Match my system", "Light", "Dark")
            (host.querySelector("select") as HTMLSelectElement).value shouldBe ""
        }
    })
