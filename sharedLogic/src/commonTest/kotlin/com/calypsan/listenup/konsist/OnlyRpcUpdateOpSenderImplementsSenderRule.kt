package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Offline entity-PATCH edits push through the single generic [RpcUpdateOpSender], parameterized by
 * an [EditableDomain]. A new hand-rolled `<Domain>UpdateOpSender` would reintroduce the copy-paste
 * this generalization removed — and re-open the drift gap where an edit routes to "no sender" and is
 * silently dropped. Pin the invariant: the only permitted [PendingOperationSender] implementations
 * are the generic sender, the router, and the two non-PATCH event/position senders.
 */
class OnlyRpcUpdateOpSenderImplementsSenderRule :
    FunSpec({
        test("only RpcUpdateOpSender (+ router + event/position senders) implement PendingOperationSender") {
            val allowlist =
                setOf(
                    "RpcUpdateOpSender",
                    "DomainPendingOperationSender",
                    "PlaybackPositionOpSender",
                    "ListeningEventOpSender",
                )
            val implementors =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { cls -> cls.parents().any { it.name == "PendingOperationSender" } }

            implementors.shouldNotBeEmpty()

            val offenders = implementors.filterNot { it.name in allowlist }.map { it.name }
            offenders.shouldBeEmpty()
        }
    })
