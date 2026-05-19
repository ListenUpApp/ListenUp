package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard: non-JVM `:shared` source sets must never import a `java.*` or
 * `javax.*` package. Those APIs do not exist on Kotlin/Native (iOS), so a stray
 * import silently breaks the Apple targets — which cannot be compiled on the
 * Linux CI runner to catch it. This rule is the portable structural guard.
 *
 * `jvmMain` / `androidMain` / `desktopMain` are intentionally not checked: the
 * JVM is a legitimate target there.
 */
class NoJvmOnlyApisInSharedRule :
    FunSpec({
        test("no non-JVM :shared source set imports java.* or javax.*") {
            val nonJvmSourceSetMarkers =
                listOf("/commonMain/", "/appleMain/", "/iosMain/", "/nativeMain/")
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { file -> file.path.contains("/shared/src/") }
                    .filter { file -> nonJvmSourceSetMarkers.any { file.path.contains(it) } }
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("java.") || it.name.startsWith("javax.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
