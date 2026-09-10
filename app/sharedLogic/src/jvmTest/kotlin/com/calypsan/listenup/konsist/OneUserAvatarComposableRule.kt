package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Only `com.calypsan.listenup.client.design.components.UserAvatar` may be
 * imported by feature code. The legacy `ProfileAvatar` and `ClickableUserAvatar`
 * composables have been deleted — this rule prevents their
 * re-introduction.
 */
class OneUserAvatarComposableRule :
    FunSpec({
        test("no feature code imports ProfileAvatar or ClickableUserAvatar") {
            val banned = listOf("ProfileAvatar", "ClickableUserAvatar")
            val clientFiles =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedUI/") || it.path.contains("/sharedLogic/") }

            assertScopeNotEmpty(
                clientFiles,
                expectedMin = 500,
                why = "sharedUI + sharedLogic files — where a deleted avatar composable could be re-imported",
            )

            val offenders =
                clientFiles
                    .flatMap { file ->
                        banned
                            .filter { ban -> file.imports.any { imp -> imp.name.endsWith(".$ban") } }
                            .map { ban -> "${file.path}: $ban" }
                    }
            offenders shouldBe emptyList()
        }
    })
