package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val fileLevelSuppress = Regex("""(?m)^\s*@file:Suppress""")

/** True when [source] opens a line with a file-level `Suppress` annotation. */
internal fun hasFileLevelSuppress(source: String): Boolean = fileLevelSuppress.containsMatchIn(source)

/**
 * No file-level suppression annotation. If a detector fires, fix the code; a file-wide
 * suppression hides every future finding of that rule in the file, not just the one someone
 * looked at. Scope a suppression to the single declaration that earns it.
 */
class NoFileLevelSuppressRule :
    FunSpec({
        test("no Kotlin source file carries a file-level Suppress annotation") {
            val sources = walkKotlinSources()

            assertScopeNotEmpty(
                sources,
                expectedMin = 3000,
                why = "Kotlin files across every source set of every module",
            )

            sources.filter { hasFileLevelSuppress(it.text) }.map { it.relativePath } shouldBe emptyList()
        }
    })
