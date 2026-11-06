package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private const val BASELINE_PATH = "app/sharedLogic/src/jvmTest/resources/code-citation-baseline.txt"

/**
 * A comment line that points at a plan or spec code: `spec §3.2`, `Decision 11`, `Task 14`, `PT8`, `H24`, `PR 3/6`.
 * `H1`..`H6` are HTML heading levels, not codes.
 */
private val CITATION_LINE =
    Regex(
        """(//|\*).*(spec §|§ ?\d|\bDecision \d+\b|\bTask \d+\b|\bPT\d+\b|\bPR \d+/\d+\b|\bgap \d+\b""" +
            """|\bopen question \d+\b|\b(?!H[1-6]\b)[HCLDQ]\d{1,2}\b|\bP-R\d+\b)""",
    )

/** A `§` that belongs to a public standard a reader can open: `RFC 1951 §3.2.7`, `ISO/IEC 14496-1 §7.2`. */
private val STANDARD_CITATION =
    Regex("""(?:RFC \d+|ISO/IEC [\d-]+|ISO [\d-]+|WCAG [\d.]+)[^§\n]{0,3}§ ?\d[\d.]*(?:\(\w+\))*""")

private val SWIFT_ROOTS = listOf("app/iosApp/ListenUp", "app/iosApp/ListenUpActivityKit")
private val SKIPPED_DIRECTORIES = setOf("build", ".build", "DerivedData")

/**
 * How many lines of [text] cite a plan or spec code instead of stating the rule they stand for.
 * Citations of public standards (`RFC 1951 §3.2.7`) do not count.
 */
internal fun citationLineCount(text: String): Int =
    text.lineSequence().count { line -> CITATION_LINE.containsMatchIn(STANDARD_CITATION.replace(line, "")) }

private fun parseBaseline(text: String): Map<String, Int> =
    text
        .lineSequence()
        .filter { it.isNotBlank() }
        .associate { line -> line.substringBeforeLast(' ') to line.substringAfterLast(' ').toInt() }

private fun isProductionSourceSet(relativePath: String): Boolean {
    val sourceSet = relativePath.substringAfter("/src/", missingDelimiterValue = "").substringBefore('/')
    return !sourceSet.contains("test", ignoreCase = true)
}

private fun productionSwiftFiles(root: File): List<Pair<String, String>> =
    SWIFT_ROOTS.flatMap { swiftRoot ->
        File(root, swiftRoot)
            .walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.extension == "swift" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toList()
    }

/**
 * Comments cite only what a reader can open: a public standard or a path in this repository.
 *
 * The design specs and plans that earlier comments pointed at (`spec §2.3`, `Task 14`, `PT8`, `H24`) are
 * not in the repository, so the pointer says nothing. Existing citations are ratcheted: the baseline in
 * `code-citation-baseline.txt` may only shrink, and a file absent from it must have none.
 */
class CommentsCiteOpenableSourcesRule :
    FunSpec({
        test("comments cite no plan or spec code beyond the shrinking baseline") {
            val root = File(Konsist.projectRootPath)
            val kotlin =
                walkKotlinSources(root)
                    .filter { isProductionSourceSet(it.relativePath) }
                    .map { it.relativePath to it.text }
            val files = kotlin + productionSwiftFiles(root)

            assertScopeNotEmpty(files, expectedMin = 2500, why = "production Kotlin and Swift sources")

            val baseline = parseBaseline(File(root, BASELINE_PATH).readText())
            val counts =
                files
                    .associate { (path, text) -> path to citationLineCount(text) }
                    .filterValues { it > 0 }

            val grew =
                counts
                    .filter { (path, count) -> count > (baseline[path] ?: 0) }
                    .map { (path, count) -> "$path now cites $count line(s), baseline ${baseline[path] ?: 0}" }
            val shrank =
                baseline
                    .filter { (path, was) -> (counts[path] ?: 0) < was }
                    .map { (path, was) ->
                        "$path now cites ${counts[path] ?: 0} line(s), baseline $was: tighten the baseline"
                    }

            (grew + shrank).sorted() shouldBe emptyList()
        }
    })
