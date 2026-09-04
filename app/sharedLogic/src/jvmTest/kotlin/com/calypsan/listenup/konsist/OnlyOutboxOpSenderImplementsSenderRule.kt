package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Offline outbox writes push through the single generic [OutboxOpSender], parameterized
 * by an [OutboxChannel]. A hand-rolled `<Domain>OpSender` would reintroduce the copy-paste
 * this generalization removed — and re-open the drift gap where an edit routes to "no
 * sender" and is silently dropped. Pin the invariant: the only permitted
 * [PendingOperationSender] implementations are the generic sender and the byDomain router.
 */
class OnlyOutboxOpSenderImplementsSenderRule :
    FunSpec({
        test("only OutboxOpSender (+ the byDomain router) implement PendingOperationSender") {
            val allowlist = setOf("OutboxOpSender", "DomainPendingOperationSender")
            val implementors =
                productionScope()
                    .classes()
                    .filter { cls -> cls.parents().any { it.name == "PendingOperationSender" } }

            implementors.shouldNotBeEmpty()

            val offenders = implementors.filterNot { it.name in allowlist }.map { it.name }
            offenders.shouldBeEmpty()
        }
    })
