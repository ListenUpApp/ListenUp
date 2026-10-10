package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [NoThrowsInDataLayerRule]'s per-line matcher.
 *
 * The rule's own test asserts the data layer is clean, so it passes whether the matcher works or
 * not. `error(...)` once escaped the data layer as an untyped IllegalStateException while the rule
 * stayed green because it only looked for the word `throw`. Plant the violations and demand they
 * are caught; plant the sanctioned shapes and demand they are not.
 */
class NoThrowsInDataLayerRuleSelfTest :
    FunSpec({

        test("a plain throw is disallowed") {
            """throw IllegalStateException("x")""".isDisallowedThrowLine() shouldBe true
        }

        test("error(...) is disallowed") {
            """error("x")""".isDisallowedThrowLine() shouldBe true
        }

        test("require(...) is disallowed") {
            "require(x)".isDisallowedThrowLine() shouldBe true
        }

        test("a cancellation rethrow is allowed") {
            "throw e".isDisallowedThrowLine() shouldBe false
        }

        test("a rethrow of an identifier that merely starts with e is disallowed") {
            "throw exception".isDisallowedThrowLine() shouldBe true
        }

        test("a NotImplementedError placeholder is allowed") {
            """throw NotImplementedError("later")""".isDisallowedThrowLine() shouldBe false
        }

        test("the not-connected guard is allowed") {
            "throw ServerUrlNotConfiguredException()".isDisallowedThrowLine() shouldBe false
        }

        test("a comment line that mentions a throw is allowed") {
            "// throw IllegalStateException()".isDisallowedThrowLine() shouldBe false
        }

        test("a line with no throw token is allowed") {
            "val x = compute()".isDisallowedThrowLine() shouldBe false
        }
    })
