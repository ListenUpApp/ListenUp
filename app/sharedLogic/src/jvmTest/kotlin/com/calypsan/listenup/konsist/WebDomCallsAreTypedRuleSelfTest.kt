package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [WebDomCallsAreTypedRule]'s matcher, so it is known to fire. */
class WebDomCallsAreTypedRuleSelfTest :
    FunSpec({
        test("a planted asDynamic() and a planted js(...) are each counted") {
            val source =
                """
                |fun f(e: Element) {
                |    e.asDynamic().setPointerCapture(1)
                |    val o = js("{}")
                |    window.navigator.asDynamic().share
                |}
                """.trimMargin()

            untypedDomCalls(source) shouldBe 3
        }

        test("mentions in comments and KDoc are not calls") {
            val source =
                """
                |// element.asDynamic().focus()
                |/**
                | * Not written as js("new X()") because asDynamic() hides typos.
                | */
                |/* js("{}") */
                |fun f() = Unit
                """.trimMargin()

            untypedDomCalls(source) shouldBe 0
        }

        test("look-alike names are not calls") {
            val source =
                """
                |val a = json("{}")
                |val b = isDynamic()
                |val c = Foo.fromJs(1)
                """.trimMargin()

            untypedDomCalls(source) shouldBe 0
        }
    })
