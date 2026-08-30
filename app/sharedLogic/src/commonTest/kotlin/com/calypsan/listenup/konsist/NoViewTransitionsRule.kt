package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * The browser's View Transition API may not return to this codebase.
 *
 * Not a style preference — a measured incompatibility, and one already paid for twice. The API
 * requires the DOM change to land inside its update callback, and **Compose HTML cannot render in
 * there**: the browser suppresses rendering while the callback is outstanding, and Compose's
 * scheduler needs a frame to flush. Measured on the book grid, the destination had still not
 * rendered **361 ms** into the callback, so every transition photographed an identical before and
 * after — it animated nothing while holding the old page frozen for the settle, which is exactly
 * what read as a lurch and a flash on every navigation.
 *
 * A second measurement makes the ceiling worse rather than better: naming all 1,204 cards took
 * **12.2 s** to reach `ready` against **41 ms** for the ~28 in the viewport, and a sort that costs
 * 2,525 ms of recomposition on its own cost **4,651 ms** wrapped in a transition, because the
 * transition waits for that recomposition with the *old* grid on screen throughout.
 *
 * `ViewTransitions.kt` was deleted for these reasons. The rule exists because the deletion left
 * behind CSS that could never match and a KDoc claiming the crossfade still worked — evidence that
 * a reader can easily conclude the mechanism is merely switched off rather than structurally
 * unavailable, and switch it back on.
 *
 * **What to use instead:** drive the Web Animations API directly. `HeroFlight` flies the cover with
 * FLIP and `PageFade` fades the page; neither cares when any framework renders, which is the whole
 * property the View Transition API cannot offer here.
 *
 * Matched on file text rather than imports, because the call is reached dynamically
 * (`document.asDynamic().startViewTransition`) and so never appears in an import list.
 */
class NoViewTransitionsRule :
    FunSpec({
        test("the scope actually reaches the web client, or this rule is vacuous") {
            // A text rule over an empty scope passes for the wrong reason. `:app:webApp` is the
            // only module that could ever call this API, so if the scope cannot see it the rule
            // guards nothing at all — and would go on reporting green forever.
            val webFiles = productionScope().files.filter { "/app/webApp/" in it.path }

            webFiles.shouldNotBeEmpty()
        }

        test("no production code starts a browser View Transition") {
            val offenders =
                productionScope()
                    .files
                    .filter { file -> BANNED_NAMES.any { it in file.text } }
                    .map { it.path }

            offenders.shouldBeEmpty()
        }
    })

/**
 * The API surface, by name. `startViewTransition` starts one; `viewTransitionName` is how elements
 * opt into being snapshotted, and is the half whose cost collapses on a long list — so banning only
 * the first would leave the expensive mistake reachable.
 */
private val BANNED_NAMES =
    setOf(
        "startViewTransition",
        "viewTransitionName",
    )
