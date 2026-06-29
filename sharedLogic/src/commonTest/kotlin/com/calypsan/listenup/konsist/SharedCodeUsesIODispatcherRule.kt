package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard: shared (multiplatform) source sets must use
 * [com.calypsan.listenup.core.IODispatcher], never a raw `Dispatchers.IO`.
 *
 * `Dispatchers.IO` is a JVM member but only reachable on Kotlin/Native through
 * the `kotlinx.coroutines.IO` extension import — a bare `Dispatchers.IO` in
 * common code compiles on the JVM/Android targets and silently breaks the Apple
 * targets, which the Linux CI runner cannot compile to catch. Routing every
 * shared call site through the single `IODispatcher` expect/actual removes the
 * footgun and keeps one canonical IO dispatcher across the codebase (it resolves
 * to the real elastic IO pool on every platform — see `core.Dispatchers`).
 *
 * Scope is every non-JVM-specific source set across all modules: `commonMain`
 * and the Apple/Native sets. The JVM-only sets (`jvmMain` / `androidMain` /
 * `desktopMain`) may use `Dispatchers.IO` directly — that is where the
 * `IODispatcher` actuals legitimately bind to it.
 */
class SharedCodeUsesIODispatcherRule :
    FunSpec({
        test("shared source sets use IODispatcher, not a raw Dispatchers.IO") {
            val sharedSourceSetMarkers =
                listOf("/commonMain/", "/appleMain/", "/iosMain/", "/nativeMain/", "/macosMain/")

            val offenders =
                productionScope()
                    .files
                    .filter { file -> sharedSourceSetMarkers.any { file.path.contains(it) } }
                    // The IODispatcher expect/actual itself is the sanctioned home of the
                    // platform dispatcher choice — it necessarily names Dispatchers.IO.
                    .filterNot { file -> file.path.endsWith("/core/Dispatchers.apple.kt") }
                    .filter { file -> file.usesRawIoDispatcher() }
                    .map { file -> file.path }

            offenders.shouldBeEmpty()
        }
    })

/** True if a non-comment line accesses `Dispatchers.IO` directly. */
private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.usesRawIoDispatcher(): Boolean =
    text.lineSequence().any { rawLine ->
        val line = rawLine.trim()
        val isComment = line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
        !isComment && line.contains("Dispatchers.IO")
    }
