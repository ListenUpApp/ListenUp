package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class WebClassesAreDefinedRuleSelfTest :
    FunSpec({
        test("a literal class with no selector is the difference between what is used and what is defined") {
            val used = literalClassesIn("""Div({ classes("known", "missing") })""")
            val defined = selectorsIn(".known { color: red }")

            used - defined shouldBe setOf("missing")
        }

        test("templated and prefix names and commented-out selectors are not read as classes") {
            literalClassesIn("""classes("a", "b-${'$'}{kind}")""") shouldBe setOf("a")
            literalClassesIn("""classes("progress-" + look)""") shouldBe emptySet()
            selectorsIn("/* .gone { } */ .kept { }") shouldBe setOf("kept")
        }
    })
