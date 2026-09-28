package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Feature screens draw a color-block hero's rounded bottom edge through `ContentShapes.hero` (or
 * `ColorBlockHero`, which uses it), never a hand-rolled `RoundedCornerShape(bottomStart = …)`.
 *
 * DESIGN.md's One Hero Rule: every hero ends in the same bottom radius. The 2026-09-27 Android audit
 * found 14 heroes hand-rolling it at 32, 36, 40 and 44dp, so no two neighbouring screens agreed and a
 * retune reached none of them. They now share the token; this rule stops a fifteenth drifting off.
 *
 * It matches a `RoundedCornerShape(` whose first argument is `bottomStart =` — the bottom-only shape a
 * hero band uses. Shapes that lead with a top corner (a decorative blob, a connected button group)
 * are geometry and are left alone. [HERO_SHAPE_ALLOWLIST] exempts a file by path suffix, with a reason.
 * Comments are skipped, so KDoc may still quote the old shape.
 */
class HeroesUseTheHeroShapeRule :
    FunSpec({
        test("no feature file hand-rolls a bottom-rounded hero shape") {
            val featureFiles =
                productionScope()
                    .files
                    .filter { "/app/sharedUI/" in it.path && "/features/" in it.path }

            assertScopeNotEmpty(
                featureFiles,
                expectedMin = 200,
                why = "every :app:sharedUI features/ production file — the screens whose heroes this pins",
            )

            featureFiles
                .filter { file -> HERO_SHAPE_ALLOWLIST.keys.none { file.path.endsWith(it) } }
                .flatMap { file -> handRolledHeroShapeLines(file.text).map { line -> "${file.path}:$line" } }
                .shouldBeEmpty()
        }
    })

/**
 * Files allowed to keep a hand-rolled bottom-rounded shape, keyed by path suffix, valued by the
 * reason. Empty at introduction: every one in features/ was a hero, and all now use the token.
 */
internal val HERO_SHAPE_ALLOWLIST: Map<String, String> = emptyMap()

private val HAND_ROLLED_HERO_SHAPE = Regex("""\bRoundedCornerShape\(\s*bottomStart\s*=""")

/** 1-based line of every `RoundedCornerShape(bottomStart = …` in [text], ignoring comments. */
internal fun handRolledHeroShapeLines(text: String): List<Int> {
    val code = blankComments(text)
    return HAND_ROLLED_HERO_SHAPE
        .findAll(code)
        .map { match -> code.substring(0, match.range.first).count { it == '\n' } + 1 }
        .toList()
}
