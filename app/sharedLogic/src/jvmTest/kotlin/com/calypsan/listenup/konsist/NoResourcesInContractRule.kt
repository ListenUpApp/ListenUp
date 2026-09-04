package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The `@Resource` blob routes are server-side only. They live in `:server`
 * (`routes/resources/`), NOT in `:contract`, so they never enter the iOS Swift framework
 * (`:app:sharedLogic` exports `:contract` wholesale) or the future JS bundle. This rule pins
 * that boundary: a new `@Resource` added to `:contract` would silently re-bloat every client
 * export, so make it a build failure.
 *
 * This is about *placement* only. [NoRestSurfaceRegrowthRule] is the one that governs whether a
 * given `@Resource` may exist at all.
 */
class NoResourcesInContractRule :
    FunSpec({
        test("no :contract type is annotated @Resource (REST surface lives in :server)") {
            val offenders =
                productionScope()
                    .classes(includeNested = true)
                    .filter { "/contract/src/" in it.path }
                    .filter { cls -> cls.annotations.any { it.name == "Resource" } }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }
    })
