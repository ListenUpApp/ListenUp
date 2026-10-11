package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** True when [bytes] contain a NUL byte, which makes git treat the file as binary. */
internal fun containsNulByte(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

/**
 * Kotlin sources are text. A raw NUL byte makes git classify the file as binary: its diffs stop
 * rendering in review, `git grep` prints "Binary file matches" instead of the line, and every
 * text-based tool silently skips it. Write the escape (`\u0000`) instead of the byte.
 */
class SourceFilesAreTextRule :
    FunSpec({
        test("no Kotlin source file contains a raw NUL byte; write the escape instead") {
            val sources = walkKotlinSources()

            assertScopeNotEmpty(
                sources,
                expectedMin = 3000,
                why = "Kotlin files across every source set of every module — the surface git must treat as text",
            )

            sources.filter { containsNulByte(it.bytes) }.map { it.relativePath } shouldBe emptyList()
        }
    })
