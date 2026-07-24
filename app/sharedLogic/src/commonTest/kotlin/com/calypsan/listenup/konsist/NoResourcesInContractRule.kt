package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The REST `@Resource` route surface is server-side only — third-party REST integrations
 * consume it; first-party Kotlin clients use the `@Rpc` proxies. It lives in `:server`
 * (`routes/resources/`), NOT in `:contract`, so it never enters the iOS Swift framework
 * (`:app:sharedLogic` exports `:contract` wholesale) or the future JS bundle. This rule pins
 * that boundary: a new `@Resource` added to `:contract` would silently re-bloat every
 * client export, so make it a build failure.
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
