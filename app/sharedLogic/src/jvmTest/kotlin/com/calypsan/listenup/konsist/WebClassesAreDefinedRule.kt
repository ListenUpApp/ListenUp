package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private const val WEB_MAIN_SOURCES = "app/webApp/src/jsMain/"
private const val WEB_STYLESHEETS = "app/webApp/web/src/css"

private val classesCall = Regex("""\bclasses\(""")

// A literal ending in `-` is the head of a name built at run time (`"progress-" + look`), not a class.
private val classLiteral = Regex(""""([a-z0-9]+(?:-[a-z0-9]+)*)"""")
private val cssComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val cssClassSelector = Regex("""\.([A-Za-z_-][A-Za-z0-9_-]*)""")

/**
 * These classes were present when [WebClassesAreDefinedRule] was introduced and each needs triage: define the rule,
 * drop the class, or record why it is a deliberate unstyled marker.
 *
 * The set only shrinks: the rule also fails when an entry becomes defined or is no longer used.
 */
private val KNOWN_UNDEFINED: Set<String> =
    setOf(
        "author-n",
        "bd-vis",
        "boost-reset",
        "keys-or",
        "lapse-act",
        "lib-show-all",
        "sd-group-fold",
        "sd-group-plain",
        "sel-target-on",
        "sh-earlier",
        "sh-later",
        "sleep-extend",
        "sleep-start",
        "speed-reset",
        "sync-ind-t",
    )

/** Index just past the `)` closing the call whose `(` sits at [open], stepping over string literals. */
private fun endOfCall(
    source: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < source.length) {
        when (source[i]) {
            '"' -> i = endOfString(source, i)
            '(' -> depth++
            ')' -> if (--depth == 0) return i + 1
        }
        i++
    }
    return source.length
}

/** Index of the `"` closing the string literal opened at [open]; a `${…}` template is stepped over whole. */
private fun endOfString(
    source: String,
    open: Int,
): Int {
    var i = open + 1
    while (i < source.length && source[i] != '"') {
        when {
            source[i] == '\\' -> i += 2
            source.startsWith("\${", i) -> i = endOfTemplate(source, i + 1) + 1
            else -> i++
        }
    }
    return i
}

/** Index of the `}` closing the template whose `{` sits at [open]. */
private fun endOfTemplate(
    source: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < source.length) {
        when (source[i]) {
            '"' -> i = endOfString(source, i)
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
        i++
    }
    return source.length
}

/** Every whole-string class name inside a `classes(…)` call in [source]; computed arguments are ignored. */
internal fun literalClassesIn(source: String): Set<String> =
    classesCall
        .findAll(source)
        .flatMap { call ->
            val open = call.range.last
            classLiteral.findAll(source.substring(open, endOfCall(source, open))).map { it.groupValues[1] }
        }.toSet()

/** Every class selector in [css], comments excluded. */
internal fun selectorsIn(css: String): Set<String> =
    cssClassSelector.findAll(css.replace(cssComment, "")).map { it.groupValues[1] }.toSet()

/**
 * Every literal class the web client's Kotlin names in `classes(...)` must have a selector in a stylesheet.
 *
 * The browser-side class contract only sees what a spec mounts, so a class on a page nobody registered renders
 * unstyled without anything failing. This rule reads the source text instead, so it covers every literal.
 */
class WebClassesAreDefinedRule :
    FunSpec({
        test("every literal class passed to classes() has a selector in the web stylesheets") {
            val usedIn: Map<String, List<String>> =
                walkKotlinSources()
                    .filter { it.relativePath.startsWith(WEB_MAIN_SOURCES) }
                    .flatMap { file -> literalClassesIn(file.text).map { it to file.relativePath } }
                    .groupBy({ it.first }, { it.second })

            assertScopeNotEmpty(
                usedIn.keys,
                expectedMin = 1500,
                why = "distinct literal classes passed to classes() across app/webApp/src/jsMain",
            )

            val stylesheets =
                File(Konsist.projectRootPath, WEB_STYLESHEETS)
                    .listFiles { file -> file.extension == "css" }
                    .orEmpty()
            assertScopeNotEmpty(stylesheets.toList(), expectedMin = 20, why = "stylesheets under $WEB_STYLESHEETS")
            val defined = stylesheets.flatMap { selectorsIn(it.readText()) }.toSet()

            val undefined = usedIn.filterKeys { it !in defined }
            val unexpected = undefined.filterKeys { it !in KNOWN_UNDEFINED }
            val nowDefined = KNOWN_UNDEFINED.filter { it in defined }
            val nowUnused = KNOWN_UNDEFINED.filter { it !in usedIn }

            unexpected.map { (name, files) -> "$name (used in ${files.distinct().sorted()})" }.sorted() shouldBe
                emptyList()
            nowDefined shouldBe emptyList()
            nowUnused shouldBe emptyList()
        }
    })
