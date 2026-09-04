package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.KoModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Repository implementations are `:app:sharedLogic` plumbing — clients depend on the
 * `domain/repository` interfaces, never the impls. Keeping `*Impl` classes `internal`
 * removes them from every client export path (iOS framework, Swift Export, JS, R8).
 * A new public `*Impl` would silently re-bloat the export, so make it a build failure.
 */
class RepositoryImplsAreInternalRule :
    FunSpec({
        test("data/repository *Impl classes are internal") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { "/data/repository/" in it.path && it.name.endsWith("Impl") }
                    .filterNot { it.hasModifier(KoModifier.INTERNAL) }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }
    })
