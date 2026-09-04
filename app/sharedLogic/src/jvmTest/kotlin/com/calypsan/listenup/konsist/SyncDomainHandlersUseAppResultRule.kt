package com.calypsan.listenup.konsist

import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins the per-domain sync contract: every `SyncDomainHandler<*>` impl's
 * `onEvent` and `onCatchUpItem` methods return `AppResult<Unit>` and never
 * `throw`. Mirror of [NoThrowsInDataLayerRule]'s shape.
 */
class SyncDomainHandlersUseAppResultRule :
    FunSpec({
        test("SyncDomainHandler implementations don't throw outside cancellation rethrows") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { it.parents().any { p -> p.name == "SyncDomainHandler" } }
                    .flatMap { it.functions() }
                    .filter { it.name == "onEvent" || it.name == "onCatchUpItem" }
                    .filter { fn ->
                        val text = fn.text
                        text.contains("throw ") &&
                            text.lineSequence().any { line ->
                                line.contains("throw ") &&
                                    !line.contains("throw e") &&
                                    !line.contains("throw cause")
                            }
                    }.map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
