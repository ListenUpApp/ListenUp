package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Server production code — Kotlin and SQLDelight sources alike — is explained in terms of the
 * persistence stack it runs on: SQLDelight, with migrations applied by the server's own runner.
 *
 * A comment that reasons from Exposed or Flyway describes transactions, adapters and migrations
 * that do not exist, and a reader who trusts it draws the wrong conclusion about atomicity or
 * locking. This rule fails on either name so such a comment has to be rewritten to say what the
 * code guarantees.
 *
 * "Exposed" is also an ordinary English participle ("Exposed so a test can…"), which the rule
 * leaves alone: [namesRetiredLibrary] matches the word only where the surrounding text makes it a
 * library name.
 *
 * Scope is server production sources only. Test sources and the client's Konsist rules keep their
 * historical mentions on purpose.
 */
class NoRetiredLibraryNamesRule :
    FunSpec({
        test("no server production source names a retired persistence library") {
            val sources =
                listOf("commonMain", "jvmMain", "linuxMain")
                    .map { File(Konsist.projectRootPath, "server/src/$it") }
                    .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile }.toList() }

            // Guard against a vacuous pass: a moved source set must fail loudly, not scan nothing.
            sources.size shouldBeGreaterThan 400

            val offenders =
                sources.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (line.namesRetiredLibrary()) {
                            "${file.relativeTo(File(Konsist.projectRootPath))}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }

            // Every offender, not the handful the matcher's own message prints.
            withClue(offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }

        listOf(
            "mirrors the Exposed base's orchestration",
            "under an outer Exposed read transaction",
            "the prior Exposed `InviteEntity.toDto()`",
            "Exposed's `suspendTransaction`",
            "runs the Flyway migrations",
            "raw bind values instead of Exposed-typed pairs",
            "[activeSessionRepo] is still Exposed",
        ).forEach { line ->
            test("matches the library name in: $line") {
                line.namesRetiredLibrary() shouldBe true
            }
        }

        listOf(
            "Exposed so a spec can assert which branch was taken",
            "Exposed separately only so a write test can…",
            "Exposed for tests and diagnostics",
            "Exposed on [ScanResultSummary]",
            "Exposed as a **function** rather than a top-level `val`",
        ).forEach { line ->
            test("leaves the English participle alone in: $line") {
                line.namesRetiredLibrary() shouldBe false
            }
        }
    })

private const val PAIRED_NOUNS =
    "read|write|transaction|implementation|insert|base|connection|DAO|DSL|entity|bool|retry|junction|layer|" +
        "type|Database|UserEntity|InviteEntity"

private val RETIRED_LIBRARY_SHAPES =
    listOf(
        Regex("""\bFlyway\b"""),
        // The name glued to punctuation only a noun takes: a code span, a possessive, an arrow, a hyphen.
        Regex("""\bExposed(?:`|'s|\s*→|-)"""),
        // The name followed by one of the nouns it is paired with.
        Regex("""\bExposed\s+(?:`|\[Database]|(?:$PAIRED_NOUNS)\b)"""),
        // The name behind a word that only precedes a noun.
        Regex("""\b(?:the|an|prior|former|outer|still|full|from|no)\s+Exposed\b""", RegexOption.IGNORE_CASE),
    )

/** True when this line of source text names Exposed or Flyway as a library, not "exposed" as a verb. */
internal fun String.namesRetiredLibrary(): Boolean = RETIRED_LIBRARY_SHAPES.any { it.containsMatchIn(this) }
