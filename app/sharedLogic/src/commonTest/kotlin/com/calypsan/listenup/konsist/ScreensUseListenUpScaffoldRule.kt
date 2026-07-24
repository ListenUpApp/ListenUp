package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Authenticated detail/editor screens must use [com.calypsan.listenup.client.design.components.ListenUpScaffold],
 * never the raw Material3 Scaffold. ListenUpScaffold reserves the floating mini-player's
 * footprint app-wide; a raw Scaffold silently reintroduces the overlap bug class.
 *
 * Scope: files under `sharedUI/.../features/` whose name ends in `Screen.kt`. The allowlist
 * carries the documented immersive opt-outs (full-screen surfaces that intentionally suppress
 * or replace the bar) and any non-screen exceptions.
 */
class ScreensUseListenUpScaffoldRule :
    FunSpec({
        test("feature screens use ListenUpScaffold, not raw Material3 Scaffold") {
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedUI/") }
                    .filter { it.path.contains("/features/") }
                    .filter { it.path.endsWith("Screen.kt") }
                    .filter { file -> file.imports.any { it.name == "androidx.compose.material3.Scaffold" } }
                    .filter { file -> SCAFFOLD_RULE_ALLOWLIST.none { allowed -> file.path.endsWith(allowed) } }
                    .map { it.name }

            offenders.shouldBeEmpty()
        }
    })

/**
 * Screens intentionally exempt from [ListenUpScaffold]: full-screen immersive surfaces that
 * suppress/replace the mini-player, and Shell tabs that are already padded by AppShell. Each
 * entry carries a one-line reason.
 */
private val SCAFFOLD_RULE_ALLOWLIST: Set<String> =
    setOf(
        "DocumentViewerScreen.kt", // immersive PDF/ebook reader — no bar overlay
        "NowPlayingScreen.kt", // the expanded player itself
        "HomeScreen.kt", // Shell tab — AppShell already reserves player space via its bottomBar; HomeScreen's inner Scaffold consumes the shell's contentPadding and must not re-add insets
    )
