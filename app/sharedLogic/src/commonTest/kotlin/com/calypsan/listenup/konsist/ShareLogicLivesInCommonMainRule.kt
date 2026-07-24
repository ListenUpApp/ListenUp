package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Share-link parsing and resolution are the source of Android↔iOS parity, so they must live
 * once in `commonMain` — never re-implemented in a `:app:sharedLogic` platform source set. A
 * platform-specific `ShareLinkCodec`/`ShareTargetResolver`/`ShareTarget`/`ShareResolution`/
 * `ShareLinkConstants` would silently let the two platforms drift, so make it a build failure.
 */
class ShareLogicLivesInCommonMainRule :
    FunSpec({

        test("share-link target, codec, resolver, and constants live only in commonMain") {
            val shareTypes =
                setOf(
                    "ShareTarget",
                    "ShareResolution",
                    "ShareLinkCodec",
                    "ShareTargetResolver",
                    "ShareLinkConstants",
                )
            val scope = productionScope()
            val offenders =
                (scope.classes() + scope.interfaces() + scope.objects())
                    .filter { it.name in shareTypes }
                    .filterNot { "/commonMain/" in it.path }
                    .map { "${it.name} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
