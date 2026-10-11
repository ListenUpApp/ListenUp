package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [PlaybackStartAwaitsAreBudgetedRule]'s matchers.
 *
 * **Why it exists.** The rule's own test asserts that production code is clean, so it passes
 * whether the matcher works or not. `RpcChannel.call` takes a trailing lambda, so the form that
 * rides the default timeout is `channel.call { ... }` with no parenthesis at all; a matcher that
 * only recognised `call(` was green over exactly the shape it exists to catch.
 */
class PlaybackStartAwaitsAreBudgetedRuleSelfTest :
    FunSpec({

        data class Case(
            val label: String,
            val source: String,
            val unbudgeted: Boolean,
            val idempotent: Boolean,
        )

        val cases =
            listOf(
                Case(
                    "explicit timeout, not idempotent",
                    "channel.call(timeout = BOUND, idempotent = false) { it.getPosition(id) }",
                    unbudgeted = false,
                    idempotent = false,
                ),
                Case(
                    "parenthesised call without a timeout",
                    "channel.call(idempotent = false) { it.getBook(id) }",
                    unbudgeted = true,
                    idempotent = false,
                ),
                Case(
                    "trailing-lambda call takes the default timeout",
                    "channel.call { it.prepare(id, caps, force) }",
                    unbudgeted = true,
                    idempotent = false,
                ),
                Case(
                    "trailing lambda on the next line",
                    "rpcChannel.call\n{ it.prepare(id) }",
                    unbudgeted = true,
                    idempotent = false,
                ),
                Case(
                    "callMutation in brace form",
                    "channel.callMutation { it.save(x) }",
                    unbudgeted = true,
                    idempotent = false,
                ),
                Case(
                    "callMutation with a timeout",
                    "channel.callMutation(timeout = BOUND) { it.save(x) }",
                    unbudgeted = false,
                    idempotent = false,
                ),
                Case(
                    "idempotent retry opt-in",
                    "channel.call(timeout = BOUND, idempotent = true) { it.getBook(id) }",
                    unbudgeted = false,
                    idempotent = true,
                ),
                Case(
                    "call only inside a comment line",
                    "// channel.call { it.prepare(id) }\nval x = 1",
                    unbudgeted = false,
                    idempotent = false,
                ),
                Case(
                    "no RPC await at all",
                    "val x = repository.load(id)",
                    unbudgeted = false,
                    idempotent = false,
                ),
            )

        cases.forEach { case ->
            test("hasUnbudgetedRpcAwait: ${case.label}") {
                case.source.hasUnbudgetedRpcAwait() shouldBe case.unbudgeted
            }
            test("hasIdempotentRpcAwait: ${case.label}") {
                case.source.hasIdempotentRpcAwait() shouldBe case.idempotent
            }
        }
    })
