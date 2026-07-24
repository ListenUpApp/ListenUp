package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard: `commonMain` and the Apple/Native source sets must never import a
 * `java.*` or `javax.*` package. `commonMain` is platform-agnostic by contract, and
 * those APIs do not exist on Kotlin/Native (iOS) — so a stray import either breaks
 * the Apple targets outright or quietly bars a JVM-only module (e.g. `:app:sharedUI`)
 * from ever gaining one. Either way the Linux CI runner cannot compile the Apple
 * targets to catch it, so this is the portable structural guard.
 *
 * Scope is every module's shared source sets. The JVM-specific sets (`jvmMain` /
 * `androidMain` / `desktopMain`) are intentionally excluded: the JVM is a
 * legitimate target there, and that is where `expect`/`actual` actuals bind to
 * `java.*` (e.g. `design/util/FileLastModified`).
 */
class NoJvmOnlyApisInSharedRule :
    FunSpec({
        test("no commonMain or Apple/Native source set imports java.* or javax.*") {
            val sharedSourceSetMarkers =
                listOf("/commonMain/", "/appleMain/", "/iosMain/", "/nativeMain/", "/macosMain/")
            val offenders =
                productionScope()
                    .files
                    .filter { file -> sharedSourceSetMarkers.any { file.path.contains(it) } }
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("java.") || it.name.startsWith("javax.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
