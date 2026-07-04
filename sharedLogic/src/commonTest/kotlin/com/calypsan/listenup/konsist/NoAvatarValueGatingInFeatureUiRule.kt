package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Feature UI must not gate avatar rendering on the dead `avatarValue` field.
 *
 * `avatarValue` is architecturally null (avatar bytes are resolved by user id, not by a stored
 * server URL). Two profile screens once branched on it to build an image URL, which silently
 * produced a blank avatar. All avatar rendering now flows through the canonical
 * `UserAvatar(userId)` / `rememberUserAvatarImage(userId)` resolver, which reads `public_profiles`
 * and resolves the image off local storage.
 *
 * This rule locks that in: no `sharedUI/.../features/` production file may reference `avatarValue`,
 * so the split can't silently reappear. (`hasImageAvatar` is deliberately NOT banned — it is now the
 * correct `avatarType == "image"` predicate and legitimately gates the Remove-photo control.)
 */
class NoAvatarValueGatingInFeatureUiRule :
    FunSpec({
        val avatarValueRef = Regex("""\bavatarValue\b""")

        test("no sharedUI feature file references the dead avatarValue field") {
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/sharedUI/") && it.path.contains("/features/") }
                    .filter { avatarValueRef.containsMatchIn(it.text) }
                    .map { it.path }
            offenders shouldBe emptyList()
        }
    })
