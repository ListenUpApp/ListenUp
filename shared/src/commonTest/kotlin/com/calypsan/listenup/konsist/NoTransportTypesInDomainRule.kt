package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the rule that domain code (use cases, repositories, domain models,
 * presentation ViewModels) must not import transport-layer types directly. The data layer
 * is the boundary: it consumes `kotlinx.rpc` and `io.ktor` and exposes `AppResult<T>` of
 * domain models to layers above. Domain layer mocking and platform portability both
 * depend on this separation.
 */
class NoTransportTypesInDomainRule : FunSpec({
    test("no commonMain domain code imports kotlinx.rpc or io.ktor symbols") {
        val transportPackagePrefixes = listOf(
            "kotlinx.rpc.",
            "io.ktor.",
        )
        val offenders = Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("/commonMain/") }
            .filter { it.path.contains("/domain/") }
            .flatMap { file ->
                file.imports
                    .filter { import ->
                        transportPackagePrefixes.any { import.name.startsWith(it) }
                    }
                    .map { "${file.path} -> ${it.name}" }
            }

        offenders.shouldBeEmpty()
    }
})
