package com.calypsan.listenup.web.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * Structural BFF boundary: `:web` must reach the domain only through the REST API, never by
 * importing `:server`. The non-vacuity assertion stops this guard from passing trivially if
 * the scope ever resolves to zero files.
 */
class WebBoundaryKonsistTest :
    FunSpec({
        val webFiles =
            Konsist
                .scopeFromProduction()
                .files
                .filter { it.path.contains("/web/src/main/") }

        test("the boundary scope actually finds :web production files") {
            webFiles.size shouldBeGreaterThan 0
        }

        test(":web has no import from the :server module") {
            val offenders =
                webFiles.flatMap { file ->
                    file.imports
                        .filter { it.name.startsWith("com.calypsan.listenup.server.") }
                        .map { "${file.path} -> ${it.name}" }
                }
            offenders.shouldBeEmpty()
        }
    })
