package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val paragraphRelativeAlign = Regex("""\bTextAlign\.(Start|End|Justify)\b|\bTextAlign\.Companion\.(Start|End)\b""")

/** True when [source] aligns text by the paragraph's own start or end ([paragraphRelativeAlign]). */
internal fun alignsByParagraph(source: String): Boolean = paragraphRelativeAlign.containsMatchIn(source)

/** Where the theme's direction rule is defined: the one file allowed to name the paragraph-relative alignments. */
private const val DIRECTION_RULE_FILE = "design/text/ContentTextDirection.kt"

/** The Compose UI's production sources: the shared screens, the Android and desktop shells, not their tests. */
private fun isComposeUiSource(path: String): Boolean =
    (path.startsWith("app/sharedUI/src/") || path.startsWith("app/androidApp/src/") || path.startsWith("app/desktopApp/src/")) &&
        "Test/" !in path

/**
 * Compose text reads its direction from its own words (`readingFromContent` in the theme), so `TextAlign.Start`,
 * `TextAlign.End` and `TextAlign.Justify` follow the words, not the layout: English text in an Arabic locale would drift
 * to the left edge of a mirrored screen. Align by the layout's edge instead, `TextAlign.LayoutStart` /
 * `TextAlign.LayoutEnd`; prose that should follow its own paragraphs uses the theme's `forProse()`.
 */
class TextAlignFollowsLayoutRule :
    FunSpec({
        test("no Compose UI source aligns text by the paragraph's start or end") {
            val sources = walkKotlinSources().filter { isComposeUiSource(it.relativePath) }

            assertScopeNotEmpty(
                sources,
                expectedMin = 550,
                why = "Kotlin files in :app:sharedUI, :app:androidApp and :app:desktopApp's production source sets",
            )

            sources
                .filter { !it.relativePath.endsWith(DIRECTION_RULE_FILE) && alignsByParagraph(it.text) }
                .map { it.relativePath } shouldBe emptyList()
        }

        test("no shared Compose source builds a text style that leaves its direction to the layout") {
            val sources =
                walkKotlinSources().filter {
                    it.relativePath.startsWith("app/sharedUI/src/commonMain/") && "/design/theme/" !in it.relativePath
                }

            assertScopeNotEmpty(sources, expectedMin = 400, why = "Kotlin files in :app:sharedUI commonMain")

            sources.filter { rawTextStylesWithoutDirection(it.text).isNotEmpty() }.map { it.relativePath } shouldBe
                emptyList()
        }
    })

private val textStyleConstructor = Regex("""(?<![\w.])TextStyle\(""")

/**
 * Each `TextStyle(...)` built in [source] that does not set `textDirection`, as its argument text. Such a style starts
 * from nothing, not from the theme, so its direction is left to the layout: English in an Arabic locale is laid out
 * right to left. Derive styles from the theme (`MaterialTheme.typography.x.copy(...)`) instead.
 */
internal fun rawTextStylesWithoutDirection(source: String): List<String> =
    textStyleConstructor.findAll(source).mapNotNull { match ->
        val open = match.range.last
        var depth = 0
        val close =
            (open until source.length).firstOrNull { index ->
                when (source[index]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                depth == 0
            } ?: return@mapNotNull null
        source.substring(open + 1, close).takeUnless { "textDirection" in it }
    }.toList()
