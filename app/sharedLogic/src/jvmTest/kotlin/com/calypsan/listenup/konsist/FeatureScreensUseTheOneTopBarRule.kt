package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Feature screens draw their top app bar through `ListenUpTopAppBar`, never a raw Material
 * `TopAppBar` (or its centre-aligned, medium or large siblings).
 *
 * The 2026-09-27 Android audit found 20 screens hand-assembling a raw `TopAppBar` — each with its
 * own title style, its own back button (half without a haptic), and its own copy of the heading
 * semantics. They now share the one bar, which owns the title voice, the heading, the named and felt
 * navigation button, and the status-bar inset; this rule stops a twenty-first drifting off.
 *
 * [TOP_BAR_ALLOWLIST] exempts a file by path suffix, with a reason. Comments are skipped, so KDoc may
 * still name the Material component.
 */
class FeatureScreensUseTheOneTopBarRule :
    FunSpec({
        test("no feature file composes a raw Material top app bar") {
            val featureFiles =
                productionScope()
                    .files
                    .filter { "/app/sharedUI/" in it.path && "/features/" in it.path }

            assertScopeNotEmpty(
                featureFiles,
                expectedMin = 200,
                why = "every :app:sharedUI features/ production file — the screens whose top bars this pins",
            )

            featureFiles
                .filter { file -> TOP_BAR_ALLOWLIST.keys.none { file.path.endsWith(it) } }
                .flatMap { file -> rawTopAppBarLines(file.text).map { line -> "${file.path}:$line" } }
                .shouldBeEmpty()
        }
    })

/**
 * Files allowed to compose a raw Material top app bar, keyed by path suffix, valued by the reason.
 * Empty at introduction: every feature bar was converged onto `ListenUpTopAppBar`.
 */
internal val TOP_BAR_ALLOWLIST: Map<String, String> = emptyMap()

private val RAW_TOP_APP_BAR =
    Regex("""(?<!\w)(?:CenterAligned|Medium|Large|MediumFlexible|LargeFlexible)?TopAppBar\(""")

/** 1-based line of every raw Material top-app-bar call in [text], ignoring comments. */
internal fun rawTopAppBarLines(text: String): List<Int> {
    val code = blankComments(text)
    return RAW_TOP_APP_BAR
        .findAll(code)
        .map { match -> code.substring(0, match.range.first).count { it == '\n' } + 1 }
        .toList()
}
