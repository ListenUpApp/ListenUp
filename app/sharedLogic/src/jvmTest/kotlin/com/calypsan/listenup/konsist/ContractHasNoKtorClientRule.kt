package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * `:contract` is the cross-platform wire/domain contract. It may use `io.ktor.http`/`io.ktor.utils.io`
 * (FileSource), but it must NOT depend on the Ktor CLIENT transport — that coupling moved to the client
 * with ErrorMapper. Pin the boundary so a client-transport import can't creep back into :contract.
 */
class ContractHasNoKtorClientRule :
    FunSpec({
        test("no :contract file imports io.ktor.client") {
            val contractFiles = productionScope().files.filter { "/contract/src/" in it.path }

            assertScopeNotEmpty(
                contractFiles,
                expectedMin = 120,
                why = "every :contract production file — an empty set means the module dropped out of the scope",
            )

            val offenders =
                contractFiles
                    .filter { file -> file.imports.any { it.name.startsWith("io.ktor.client.") } }
                    .map { it.path }
            offenders.shouldBeEmpty()
        }
    })
