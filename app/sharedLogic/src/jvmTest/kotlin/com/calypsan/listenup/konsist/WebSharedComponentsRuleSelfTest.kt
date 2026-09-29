package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Planted inputs for [WebSharedComponentsRule]'s three matchers, so each is known to fire. */
class WebSharedComponentsRuleSelfTest :
    FunSpec({
        test("every raw button class is caught, alone or beside a page class") {
            val source =
                """
                |Button(attrs = { classes("btn-c") }) { Text("Save") }
                |Button(attrs = { classes("btn-o", "inbox-back") }) { Text("Back") }
                |Button(attrs = { classes("iconbtn") }) { Icon(WebIcon.X) }
                |Button(attrs = { classes("btn") }) { Text("Sign in") }
                |private const val QUIET = "btn-ghost"
                """.trimMargin()

            rawButtonClassLines(source) shouldBe listOf(1, 2, 3, 4, 5)
        }

        test("the shared Button, and classes that merely start with btn, pass") {
            val source =
                """
                |Button(kind = ButtonKind.Primary, onClick = { save() }) { Text("Save") }
                |Div(attrs = { classes("btnbar", "bd-actions") }) {}
                |// Replaces classes("btn-c"), which drew its own height.
                """.trimMargin()

            rawButtonClassLines(source).shouldBeEmpty()
        }

        test("an H1 in either attrs form is caught; PageHeader and prose are not") {
            val source =
                """
                |H1 { Text("Library") }
                |H1(attrs = { classes("x-title") }) { Text("Inbox") }
                |PageHeader(title = "Inbox")
                | * The page's one H1 is its PageHeader.
                |val heading = main.querySelector("h1")
                """.trimMargin()

            pageHeadingLines(source) shouldBe listOf(1, 2)
        }

        test("a hand-drawn empty state is caught, with or without a marker class") {
            val source =
                """
                |Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
                |Div(attrs = { classes("empty", "is-error") }) {}
                |EmptyState(title = "Inbox empty")
                |Div(attrs = { classes("empty-line") }) {}
                """.trimMargin()

            rawEmptyStateLines(source) shouldBe listOf(1, 2)
        }
    })
