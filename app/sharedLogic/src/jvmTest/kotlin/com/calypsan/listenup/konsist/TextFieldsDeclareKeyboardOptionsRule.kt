package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every `ListenUpTextField` call site declares `keyboardOptions`.
 *
 * The parameter defaults to `KeyboardOptions.Default`: a plain text keyboard, no capitalization, no
 * IME action. That is the wrong answer for almost every field in the app — names and titles want
 * `Words`, a URL wants `Uri`, a search box wants the Search action — and in 2026-09 twenty-nine of
 * fifty-three call sites were shipping it. `ListenUpTextField` cannot choose for the caller, because
 * it does not know what the field is *for*; the caller has to say, and this rule makes forgetting a
 * build failure rather than a keyboard the user notices.
 *
 * `*Previews.kt` is exempt: a design preview renders the component, it is not a field anyone types
 * into — the same carve-out detekt's `MagicNumber` already makes for previews.
 */
class TextFieldsDeclareKeyboardOptionsRule :
    FunSpec({
        test("every ListenUpTextField call site declares keyboardOptions") {
            val offenders =
                productionScope()
                    .files
                    .filter { "/app/sharedUI/" in it.path && !it.path.endsWith("Previews.kt") }
                    .flatMap { file -> callsMissingKeyboardOptions(file.text).map { line -> "${file.path}:$line" } }
            offenders.shouldBeEmpty()
        }
    })

/**
 * 1-based line of every `ListenUpTextField(` call in [text] whose argument list never names
 * `keyboardOptions`. The definition (`fun ListenUpTextField(`) is not a call. Parentheses inside
 * string literals are skipped so a label like `"Name (optional)"` cannot end the scan early.
 */
internal fun callsMissingKeyboardOptions(text: String): List<Int> {
    val marker = "ListenUpTextField("
    val offenders = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(marker, from)
        if (at < 0) break
        from = at + marker.length
        if (text.substring(0, at).trimEnd().endsWith("fun")) continue

        var depth = 1
        var i = from
        var inString = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') {
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }

                c == '"' -> {
                    inString = true
                }

                c == '(' -> {
                    depth++
                }

                c == ')' -> {
                    depth--
                }
            }
            i++
        }
        if (!text.substring(from, i).contains("keyboardOptions")) {
            offenders += text.substring(0, at).count { it == '\n' } + 1
        }
    }
    return offenders
}
