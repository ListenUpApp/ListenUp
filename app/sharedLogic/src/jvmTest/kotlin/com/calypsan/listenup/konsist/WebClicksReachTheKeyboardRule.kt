package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * No clickable `Div`, `Span`, `I` or `B` in the web client unless it is a whole control.
 *
 * ⛔ **The regression this exists to stop.** The 2026-09-28 web audit found the keyboard locked out
 * of the sidebar, the account menu, all eight auth links, Book Detail's tabs, every sort row, the
 * table's checkboxes and headers, the chapter map and the toast's dismiss — every one of them an
 * `onClick` on an element the keyboard cannot reach and a screen reader announces as nothing. It
 * was one habit, repeated, so it is policed as one rule rather than fixed as ten bugs.
 *
 * An `onClick` in the attrs of a `Div`/`Span`/`I`/`B` passes only when the same attrs block also
 * sets a `role` AND handles a key (`onKeyDown`/`onKeyUp`) — the minimum for a non-native control
 * (it still needs a `tabIndex` to be reached, which review has to check). The better fix is almost
 * always a real `Button` or `A`; see `design/LinkButton.kt`, `design/SortControl.kt`,
 * `design/DataTable.kt`'s `Checkbox`.
 *
 * Matched on source text, comments stripped, over all of `:app:webApp`'s `jsMain`. It looks at
 * the element whose attrs the `onClick` sits in, walking out through `?.let { }` and `if { }`
 * wrappers, so `onSelect?.let { onClick { … } }` is still attributed to its `Div`.
 *
 * [ALLOWED] maps each tolerated site to its reason. Keyed `path#class` (the first CSS class in the
 * attrs block) rather than by line, so an unrelated edit above it does not break the key.
 */
class WebClicksReachTheKeyboardRule :
    FunSpec({
        test("no web element takes a click the keyboard cannot give it") {
            val webFiles = webSourceFiles()

            assertScopeNotEmpty(
                webFiles.keys,
                expectedMin = 150,
                why = "every :app:webApp jsMain file — if discovery breaks, this rule polices nothing",
            )

            val offenders =
                webFiles
                    .flatMap { (path, text) -> clickableNonControls(text).map { it.describe(path) } }
                    .filterNot { it.key in ALLOWED }
                    .map { it.message }

            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }

        test("every allowed site still exists") {
            // An entry that matches nothing reads like a live exception and hides the next real one.
            val found =
                webSourceFiles()
                    .flatMap { (path, text) -> clickableNonControls(text).map { it.describe(path).key } }
                    .toSet()

            val stale = ALLOWED.keys.filterNot { it in found }

            withClue(stale.joinToString("\n", prefix = "\nNo longer present — delete these lines:\n")) {
                stale.shouldBeEmpty()
            }
        }
    })

/**
 * Sites where a click on a non-control element is deliberate, each for a stated reason.
 *
 * Keep it short. "It was easier" is not a reason; the rule exists so the decision gets made.
 */
private val ALLOWED: Map<String, String> =
    mapOf(
        "features/chaptereditor/ChapterRow.kt#chr" to
            "A row holding eight buttons cannot itself be a button. The click on its body is a mouse " +
            "convenience; the title inside it (`.chr-t`) is a real button making the same selection.",
        "features/seriesedit/ParentPicker.kt#sh-chev" to
            "An aria-hidden expand chevron inside a role=\"tree\" row. The tree pattern gives the keyboard " +
            "Right/Left on the focused row for the same expand/collapse; a focusable button nested in a " +
            "treeitem would break its single tab stop.",
    )

/** `path relative to the web package` → source text, for every `:app:webApp` jsMain file. */
private fun webSourceFiles(): Map<String, String> =
    productionScope()
        .files
        .filter { "/app/webApp/src/jsMain/" in it.path }
        .associate { it.path.substringAfter("/com/calypsan/listenup/web/") to it.text }

/** One clickable non-control: the element's name, the line of the `onClick`, and its first class. */
internal data class ClickableNonControl(
    val element: String,
    val line: Int,
    val firstClass: String?,
) {
    /** The allowlist key and the message for this site in the file at [path]. */
    fun describe(path: String): Described {
        val key = "$path#${firstClass ?: "line$line"}"
        return Described(key, "$path:$line — onClick on $element without role + key handling ($key)")
    }

    /** A site with its allowlist key and its failure message. */
    data class Described(
        val key: String,
        val message: String,
    )
}

/** The element names whose `onClick` makes a mouse-only control. */
private val NON_CONTROLS = setOf("Div", "Span", "I", "B")

private val ON_CLICK = Regex("""\bonClick\s*\{""")

/**
 * The composable a `{` opens the attrs of: `Div(attrs = {`, `Div({`, `Img(src = x, attrs = {`.
 * Anchored at the end, so `if (x) {` and `?.let {` do not match and are walked out of.
 */
private val ELEMENT_ATTRS_OPENING = Regex("""\b([A-Z]\w*)\s*\(\s*(?:[^(){}]*,\s*)?(?:attrs\s*=\s*)?$""")

private val FIRST_CLASS = Regex("""classes\(\s*"([^"]+)"""")

/**
 * Every `onClick` in [source] that sits in the attrs of a `Div`, `Span`, `I` or `B` whose attrs do
 * not also set a `role` and handle a key. Comments are blanked first (newlines kept, so lines stay
 * true), because prose about an `onClick` is not one.
 */
internal fun clickableNonControls(source: String): List<ClickableNonControl> {
    val text = source.withCommentsBlanked()
    return ON_CLICK
        .findAll(text)
        .mapNotNull { click ->
            var open = enclosingOpenBrace(text, click.range.first)
            while (open != null) {
                val element =
                    ELEMENT_ATTRS_OPENING
                        .find(text.substring(maxOf(0, open - LOOKBEHIND), open))
                        ?.run { groupValues[1] }
                if (element != null) {
                    if (element !in NON_CONTROLS) return@mapNotNull null
                    val attrs = text.substring(open, matchingCloseBrace(text, open))
                    val isWholeControl =
                        "attr(\"role\"" in attrs && ("onKeyDown" in attrs || "onKeyUp" in attrs)
                    if (isWholeControl) return@mapNotNull null
                    return@mapNotNull ClickableNonControl(
                        element = element,
                        line = text.substring(0, click.range.first).count { it == '\n' } + 1,
                        firstClass = FIRST_CLASS.find(attrs)?.run { groupValues[1] },
                    )
                }
                open = enclosingOpenBrace(text, open)
            }
            null
        }.toList()
}

/** How far back from a `{` to look for the element call that opens it. */
private const val LOOKBEHIND = 200

/** The index of the unmatched `{` enclosing [from], or null at top level. */
private fun enclosingOpenBrace(
    text: String,
    from: Int,
): Int? {
    var depth = 0
    var i = from - 1
    while (i >= 0) {
        when (text[i]) {
            '}' -> {
                depth++
            }

            '{' -> {
                if (depth == 0) return i
                depth--
            }
        }
        i--
    }
    return null
}

/** The index just past the `}` matching the `{` at [open]. */
private fun matchingCloseBrace(
    text: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until text.length) {
        when (text[i]) {
            '{' -> {
                depth++
            }

            '}' -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
    }
    return text.length
}

/**
 * [this] with comments blanked and braces inside string literals blanked, newlines kept.
 *
 * A small lexer rather than two regexes, because a `//` inside `"https://…"` would otherwise eat the
 * rest of its line — `{` included — and every brace count after it would be wrong. Braces inside
 * strings go too: a template's `${…}` is balanced, but a literal `"{"` is not.
 */
private fun String.withCommentsBlanked(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val span = spanAt(i)
        for (k in i until span.end) out.append(span.kind.render(this[k]))
        i = span.end
    }
    return out.toString()
}

/** What a stretch of source is, as far as counting braces goes. */
private enum class SpanKind {
    CODE,
    COMMENT,
    LITERAL,
    ;

    fun render(c: Char): Char =
        when (this) {
            CODE -> c
            COMMENT -> if (c == '\n') '\n' else ' '
            LITERAL -> if (c == '{' || c == '}') ' ' else c
        }
}

/** A stretch of source ending (exclusive) at [end]. */
private data class Span(
    val end: Int,
    val kind: SpanKind,
)

/** The stretch of source that starts at [i]: a comment, a literal, or one character of code. */
private fun String.spanAt(i: Int): Span =
    when {
        startsWith("//", i) -> Span(indexOf('\n', i).orLength(this), SpanKind.COMMENT)
        startsWith("/*", i) -> Span(indexOf("*/", i + 2).orLength(this, past = 2), SpanKind.COMMENT)
        startsWith(RAW_QUOTE, i) -> Span(indexOf(RAW_QUOTE, i + 3).orLength(this, past = 3), SpanKind.LITERAL)
        this[i] == '"' -> Span(stringEnd(i), SpanKind.LITERAL)
        this[i] == '\'' -> Span(charLiteralEnd(i), SpanKind.LITERAL)
        else -> Span(i + 1, SpanKind.CODE)
    }

/** Just past the closing quote of the string opened at [open], skipping escapes. */
private fun String.stringEnd(open: Int): Int {
    var i = open + 1
    while (i < length && this[i] != '"' && this[i] != '\n') i += if (this[i] == '\\') 2 else 1
    return minOf(i + 1, length)
}

/** Just past a char literal at [open] — `'{'`, `'\\''` — or one character when it is not one. */
private fun String.charLiteralEnd(open: Int): Int {
    val close = indexOf('\'', if (startsWith("'\\", open)) open + 3 else open + 1)
    return if (close < 0 || close - open > CHAR_LITERAL_MAX) open + 1 else close + 1
}

/** [this] index, moved [past] a found delimiter, or the end of [text] when nothing was found. */
private fun Int.orLength(
    text: String,
    past: Int = 0,
): Int = if (this < 0) text.length else this + past

private const val RAW_QUOTE = "\"\"\""

/** The longest char literal, `'\\uXXXX'`, is seven characters past its opening quote. */
private const val CHAR_LITERAL_MAX = 7
