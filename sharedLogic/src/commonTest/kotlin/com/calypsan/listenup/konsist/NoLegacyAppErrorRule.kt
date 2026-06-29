package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard that pins the Phase 3 PR 1 deletion of the legacy
 * `client.core.error.AppError` hierarchy.
 *
 * The `client.core.error` package still holds infrastructure types that survived the
 * deletion ([com.calypsan.listenup.core.error.ErrorBus],
 * [com.calypsan.listenup.client.core.error.ErrorMapper]) — those imports are allowed.
 * `AppException` was also a survivor through Phase 3.5 but was deleted in Task 27d
 * (slice 18); any new import targeting it will simply fail to resolve, so it no
 * longer needs an allowlist entry here. Any other reference into that package
 * would be reaching for the deleted error types (or a re-introduction of them),
 * which is the regression this rule blocks.
 */
class NoLegacyAppErrorRule :
    FunSpec({
        test("no production code imports the deleted client.core.error.AppError hierarchy") {
            val allowedSurvivors =
                setOf(
                    "com.calypsan.listenup.core.error.ErrorBus",
                    "com.calypsan.listenup.client.core.error.ErrorMapper",
                )

            val offenders =
                productionScope()
                    .files
                    .flatMap { file ->
                        file.imports
                            .filter { import ->
                                import.name.startsWith("com.calypsan.listenup.core.error.") &&
                                    allowedSurvivors.none { allowed ->
                                        import.name == allowed ||
                                            import.name.startsWith("$allowed.")
                                    }
                            }.map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
