package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withPublicOrDefaultModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the rule that every public type defined in commonMain has a
 * KDoc block.
 *
 * KDoc on commonMain types is load-bearing — the same type is consumed from Android,
 * desktop, iOS, and (sometimes) the server. A reader scanning the type to understand
 * how to use it doesn't have a parent class or surrounding callsite to lean on; KDoc
 * is what carries the contract across platforms.
 */
class PublicCommonMainTypesHaveKDocRule :
    FunSpec({
        test("every public commonMain class/interface has a KDoc block") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { it.path.contains("/commonMain/") }
                    .withPublicOrDefaultModifier()
                    .filter { !it.hasKDoc }
                    .map { "${it.fullyQualifiedName} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
