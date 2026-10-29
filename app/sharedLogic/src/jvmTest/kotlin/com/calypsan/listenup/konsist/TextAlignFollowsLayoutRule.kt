package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val paragraphRelativeAlign = Regex("""\bTextAlign\.(Start|End)\b""")

/** True when [source] aligns text by the paragraph's own start or end ([paragraphRelativeAlign]). */
internal fun alignsByParagraph(source: String): Boolean = paragraphRelativeAlign.containsMatchIn(source)

/** The Compose UI's production sources: the screens and the desktop shell, not their tests. */
private fun isComposeUiSource(path: String): Boolean =
    (path.startsWith("app/sharedUI/src/") || path.startsWith("app/desktopApp/src/")) && "Test/" !in path

/**
 * Compose text reads its direction from its own words (`readingFromContent` in the theme), so `TextAlign.Start` and
 * `TextAlign.End` follow the words, not the layout: English text in an Arabic locale would drift to the left edge of a
 * mirrored screen. Align by the layout's edge instead, `TextAlign.LayoutStart` / `TextAlign.LayoutEnd`.
 */
class TextAlignFollowsLayoutRule :
    FunSpec({
        test("no Compose UI source aligns text by the paragraph's start or end") {
            val sources = walkKotlinSources().filter { isComposeUiSource(it.relativePath) }

            assertScopeNotEmpty(
                sources,
                expectedMin = 550,
                why = "Kotlin files in :app:sharedUI and :app:desktopApp's production source sets",
            )

            sources.filter { alignsByParagraph(it.text) }.map { it.relativePath } shouldBe emptyList()
        }
    })
