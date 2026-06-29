package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Feature screens must not hand-roll status-bar insets with `statusBarsPadding` /
 * `systemBarsPadding`. The canonical `ListenUpTopAppBar` (standard screens) and the immersive
 * `*Hero` components (which use `windowInsetsPadding(WindowInsets.statusBars)`) own the inset.
 * This keeps the "every new page must manually patch insets" foot-gun out of `sharedUI`.
 *
 * The allowlist holds the CURRENT legitimate users (self-contained scaffolds / sheets that own
 * their full system-bar inset). This rule is a ratchet: it locks today's good state and blocks
 * NEW hand-rolled status-bar padding in features.
 */
class NoManualStatusBarInsetInFeaturesRule :
    FunSpec({
        test("no sharedUI feature file imports statusBarsPadding/systemBarsPadding outside the allowlist") {
            val banned =
                listOf(
                    "androidx.compose.foundation.layout.statusBarsPadding",
                    "androidx.compose.foundation.layout.systemBarsPadding",
                )
            val allowlist =
                listOf(
                    "features/auth/PendingApprovalScreen.kt",
                    "features/auth/LoginScreen.kt",
                    "features/auth/components/AuthScaffold.kt",
                    "features/auth/CreateAccountScreen.kt",
                    "features/nowplaying/CompactNowPlaying.kt",
                    "features/setup/LibrarySetupScreen.kt",
                    "features/setup/scan/LibraryScanScreen.kt",
                )
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedUI/") }
                    .filter { f -> allowlist.none { f.path.endsWith(it) } }
                    .flatMap { file ->
                        file.imports
                            .filter { imp -> banned.any { imp.name == it } }
                            .map { imp -> "${file.path}: ${imp.name}" }
                    }
            offenders shouldBe emptyList()
        }
    })
