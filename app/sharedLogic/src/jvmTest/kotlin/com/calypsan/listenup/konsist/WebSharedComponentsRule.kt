package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The web client draws a button, a page title and an empty state one way each — through its shared
 * composables — and never by hand.
 *
 * ⛔ **The regression this exists to stop.** The 2026-09-28 web audit counted 144 raw button class
 * strings across six overlapping classes, 31 private page-title rules drifting between 1.5rem and
 * 2.1rem (and pages with no H1 at all while they loaded), and 21 private empty-state variants. Each
 * was the same habit: a page re-rolling a look instead of reaching for the component. Pass 5 moved
 * every site onto `design/Button.kt`, `design/PageHeader.kt` and `design/EmptyState.kt`; this keeps
 * the next page from rolling its own again.
 *
 * Three checks over all of `:app:webApp`'s `jsMain`, on source text with comment lines skipped:
 * - no `"btn…"`/`"iconbtn"` class literal outside `design/Button.kt` — use `Button(kind = …)`;
 * - no `H1` outside `design/PageHeader.kt` — a page's one H1 is its `PageHeader`, which is also the
 *   tab's title and the place focus lands on navigation;
 * - no `classes("empty")` outside `design/EmptyState.kt` — use `EmptyState` or `LoadingState`.
 */
class WebSharedComponentsRule :
    FunSpec({
        test("no web source draws a button with a raw btn class") {
            offenders(owner = "design/Button.kt", ::rawButtonClassLines, "a raw button class — use design.Button")
                .let { withClue(it.joinToString("\n", prefix = "\n")) { it.shouldBeEmpty() } }
        }

        test("no web source renders an H1 except PageHeader") {
            offenders(owner = "design/PageHeader.kt", ::pageHeadingLines, "an H1 — use design.PageHeader")
                .let { withClue(it.joinToString("\n", prefix = "\n")) { it.shouldBeEmpty() } }
        }

        test("no web source draws an empty state by hand") {
            offenders(owner = "design/EmptyState.kt", ::rawEmptyStateLines, "a raw .empty — use design.EmptyState")
                .let { withClue(it.joinToString("\n", prefix = "\n")) { it.shouldBeEmpty() } }
        }
    })

/** `path:line — what` for every hit of [lines] in every web file but [owner], the composable itself. */
private fun offenders(
    owner: String,
    lines: (String) -> List<Int>,
    what: String,
): List<String> {
    val files = webSources()
    assertScopeNotEmpty(
        files.keys,
        expectedMin = 150,
        why = "every :app:webApp jsMain file — if discovery breaks, this rule polices nothing",
    )
    return files
        .filterKeys { it != owner }
        .flatMap { (path, text) -> lines(text).map { "$path:$it — $what" } }
}

/** `path relative to the web package` → source text, for every `:app:webApp` jsMain file. */
private fun webSources(): Map<String, String> =
    productionScope()
        .files
        .filter { "/app/webApp/src/jsMain/" in it.path }
        .associate { it.path.substringAfter("/com/calypsan/listenup/web/") to it.text }

private val RAW_BUTTON_CLASS = Regex(""""(btn|btn-[a-z]+(-[a-z]+)*|iconbtn)"""")

private val HEADING_ONE = Regex("""\bH1\s*[({]""")

private val RAW_EMPTY_STATE = Regex("""classes\([^)]*"empty"""")

/** 1-based lines of [source] that name a button class as a string literal. */
internal fun rawButtonClassLines(source: String): List<Int> = codeLinesMatching(source, RAW_BUTTON_CLASS)

/** 1-based lines of [source] that render an `H1`. */
internal fun pageHeadingLines(source: String): List<Int> = codeLinesMatching(source, HEADING_ONE)

/** 1-based lines of [source] that give an element the `empty` class. */
internal fun rawEmptyStateLines(source: String): List<Int> = codeLinesMatching(source, RAW_EMPTY_STATE)

/**
 * Lines matching [pattern], skipping comment lines: prose that mentions `H1(` or `"btn-c"` — as this
 * file's own KDoc does — is not a use of it.
 */
private fun codeLinesMatching(
    source: String,
    pattern: Regex,
): List<Int> =
    source.lines().mapIndexedNotNull { index, line ->
        val code = line.trimStart()
        val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
        if (!isComment && pattern.containsMatchIn(line.substringBefore(" // "))) index + 1 else null
    }
