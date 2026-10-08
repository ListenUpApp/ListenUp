package com.calypsan.listenup.rpcguard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private sealed interface Content

private data class Filled(
    val amount: Int,
) : Content

private data object Empty : Content

private data class Holder(
    val content: Content,
)

/**
 * Holds `shouldBe` to plain `equals`. On Kotest 6.2.5, the JVM's field-by-field data class comparison
 * passed `Holder(Filled(1)) shouldBe Holder(Empty)`; `useStrictKotestEquality()` (build-logic's
 * KotestEquality.kt) turns it off on every JVM test task. If this fails, that setting stopped reaching
 * this module's lane, and every "this is a failure of kind X" assertion here can pass on the wrong kind.
 */
class ShouldBeIsEqualsSpec :
    FunSpec({
        test("a nested data class is not equal to a nested data object") {
            shouldThrow<AssertionError> { Holder(Filled(1)) shouldBe Holder(Empty) }
            shouldThrow<AssertionError> { Holder(Empty) shouldBe Holder(Filled(1)) }
        }

        test("equal values still pass") {
            Holder(Filled(1)) shouldBe Holder(Filled(1))
            Holder(Empty) shouldBe Holder(Empty)
        }
    })
