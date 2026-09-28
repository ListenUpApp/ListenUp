package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Planted inputs for [clickableNonControls], so the rule is known to fire before it is trusted. */
class WebClicksReachTheKeyboardRuleSelfTest :
    FunSpec({
        test("a clickable div is reported with its line and first class") {
            val source =
                """
                |@Composable
                |fun Row() {
                |    Div(attrs = {
                |        classes("nav-i", "on")
                |        onClick { go() }
                |    }) { Text("Home") }
                |}
                """.trimMargin()

            clickableNonControls(source) shouldBe listOf(ClickableNonControl("Div", line = 5, firstClass = "nav-i"))
        }

        test("Span, I and B are caught as well, in either attrs form") {
            val source =
                """
                |Span(attrs = { onClick { a() } }) {}
                |I({ onClick { b() } }) {}
                |B(attrs = { classes("x"); onClick { c() } }) {}
                """.trimMargin()

            clickableNonControls(source).map { it.element } shouldBe listOf("Span", "I", "B")
        }

        test("an onClick behind ?.let or if is still charged to its element") {
            val source =
                """
                |Div(attrs = {
                |    classes("tab")
                |    onSelect?.let { select -> onClick { select(key) } }
                |    if (enabled) {
                |        onClick { other() }
                |    }
                |}) {}
                """.trimMargin()

            clickableNonControls(source).map { it.line } shouldBe listOf(3, 5)
        }

        test("a role plus a key handler makes it a whole control") {
            val source =
                """
                |Div(attrs = {
                |    classes("card")
                |    tabIndex(0)
                |    attr("role", "button")
                |    onKeyDown { event -> if (event.key == "Enter") open() }
                |    onClick { open() }
                |}) {}
                """.trimMargin()

            clickableNonControls(source).shouldBeEmpty()
        }

        test("a role alone, or a key handler alone, is not enough") {
            val roleOnly =
                """
                |Span(attrs = {
                |    attr("role", "button")
                |    onClick { dismiss() }
                |}) {}
                """.trimMargin()
            val keysOnly =
                """
                |Span(attrs = {
                |    onKeyDown { dismiss() }
                |    onClick { dismiss() }
                |}) {}
                """.trimMargin()

            clickableNonControls(roleOnly).size shouldBe 1
            clickableNonControls(keysOnly).size shouldBe 1
        }

        test("real controls, and onClick on other elements, are not this rule's business") {
            val source =
                """
                |Button(attrs = { onClick { save() } }) { Text("Save") }
                |A(href = "https://example.com/{x}", attrs = { onClick { follow() } }) {}
                |Td(attrs = { onToggle?.let { t -> onClick { t() } } }) {}
                """.trimMargin()

            clickableNonControls(source).shouldBeEmpty()
        }

        test("a URL, or a brace inside a string or a char, does not throw the brace count off") {
            // Without string awareness the `//` in the URL blanks the `{` after it, and the Div's
            // onClick is charged to nothing — a real offender slips through.
            val source =
                """
                |Img(src = "https://cdn/x.png", attrs = { alt("{") })
                |val open = '{'
                |Div(attrs = {
                |    onClick { go("}") }
                |}) {}
                """.trimMargin()

            clickableNonControls(source).map { it.element to it.line } shouldBe listOf("Div" to 4)
        }

        test("an onClick in a comment is prose, not a handler") {
            val source =
                """
                |// These were Div(attrs = { onClick { … } }) and could not be reached.
                |/* Span(attrs = { onClick { x() } }) */
                |Button(attrs = { onClick { go() } }) {}
                """.trimMargin()

            clickableNonControls(source).shouldBeEmpty()
        }

        test("the allowlist key names the file and the first class") {
            ClickableNonControl("Div", line = 48, firstClass = "chr")
                .describe("features/chaptereditor/ChapterRow.kt")
                .key shouldBe "features/chaptereditor/ChapterRow.kt#chr"
            ClickableNonControl("Span", line = 7, firstClass = null)
                .describe("design/X.kt")
                .key shouldBe "design/X.kt#line7"
        }
    })
