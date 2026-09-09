package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * `com.calypsan.listenup.core.AppResult` was consolidated into the single canonical
 * `com.calypsan.listenup.api.result.AppResult`. Ban any reference to the deleted type so
 * the dual-result split cannot reappear.
 */
class NoLegacyCoreAppResultRule :
    FunSpec({
        // A ban rule: the population is the whole production file set, with no narrowing before the
        // violation predicate. Its only vacuity failure mode is a collapsed scope, which
        // KonsistScopeTest's scope-sanity case covers centrally.
        test("no file imports the deleted com.calypsan.listenup.core.AppResult") {
            val offenders =
                productionScope()
                    .files
                    .filter { file -> file.imports.any { it.name == "com.calypsan.listenup.core.AppResult" } }
                    .map { it.path }
            offenders.shouldBeEmpty()
        }
    })
