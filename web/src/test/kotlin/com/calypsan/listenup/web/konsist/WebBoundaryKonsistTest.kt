package com.calypsan.listenup.web.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Structural BFF boundary: `:web` must reach the domain only through the REST API, never
 * by importing `:server`. This keeps "the web UI consumes the API" a compile-time fact.
 *
 * The rule scans every production file under the `/web/src/main/` path and asserts that
 * none import a `com.calypsan.listenup.server.*` symbol. If a web file ever reaches
 * into server internals, the server is leaking its internals into the BFF — this rule
 * fails the build either way.
 */
class WebBoundaryKonsistTest :
    FunSpec({
        test(":web has no import from the :server module") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { it.path.contains("/web/src/main/") }
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("com.calypsan.listenup.server.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
