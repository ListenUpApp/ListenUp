package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [RpcServicesAreGuardedRule]'s registration-versus-guard counter.
 *
 * The rule's own test asserts every registration is guarded, so it passes whether the counter
 * works or not. A `guard(` mentioned only in a comment must not satisfy it, and an unguarded
 * second registration must not hide behind a first guarded one.
 */
class RpcServicesAreGuardedRuleSelfTest :
    FunSpec({

        test("one guarded registration is fine") {
            "registerService<A> { guard(x) }".hasUnguardedRegistration() shouldBe false
        }

        test("two registrations with one guard is a violation") {
            "registerService<A> { guard(x) }\nregisterService<B> { y }".hasUnguardedRegistration() shouldBe true
        }

        test("a guard that appears only in a comment does not count") {
            "registerService<A> { y } // guard(y)".hasUnguardedRegistration() shouldBe true
        }

        test("a guarded scoped registration is fine") {
            "registerScoped<B> { guard(y) }".hasUnguardedRegistration() shouldBe false
        }
    })
