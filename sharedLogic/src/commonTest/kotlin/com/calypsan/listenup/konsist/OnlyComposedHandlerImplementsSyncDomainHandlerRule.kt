package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Every mirrored domain is a declarative descriptor compiled by the ONE composed
 * factory. A new hand-written `SyncDomainHandler` implementation would reopen the
 * pre-Phase-2 world of bespoke handler classes; descriptors + `toHandler` are the
 * only sanctioned path. (Test doubles live in test source sets, outside this
 * production scope.)
 */
class OnlyComposedHandlerImplementsSyncDomainHandlerRule :
    FunSpec({
        test("only the composed factory implements SyncDomainHandler in production") {
            val allowlist = setOf("ComposedSyncDomainHandler", "AccessFilteredComposedSyncDomainHandler")
            // A parent's `name` carries its type arguments (e.g. "SyncDomainHandler<T>"),
            // so compare on the bare type name before the angle bracket.
            val syncHandlerParents = setOf("SyncDomainHandler", "ComposedSyncDomainHandler")
            val implementors =
                productionScope()
                    .classes()
                    .filter { cls ->
                        cls.parents().any { it.name.substringBefore("<") in syncHandlerParents }
                    }

            implementors.shouldNotBeEmpty()

            val offenders = implementors.filterNot { it.name in allowlist }.map { "${it.name} in ${it.path}" }
            offenders.shouldBeEmpty()
        }
    })
