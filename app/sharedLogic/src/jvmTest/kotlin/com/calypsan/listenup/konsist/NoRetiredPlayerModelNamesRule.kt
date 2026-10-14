package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * The player had three state models (Android's `NowPlayingViewModel`, iOS's coordinator UI state, web's
 * `TransportState`); PR 1 collapsed them into `PlayerViewModel`. These names cannot come back, because a
 * second model is how the three drifted apart (spec §4.10).
 *
 * The match is on whole words anywhere in a production file, comments included: a KDoc naming a
 * retired model points the reader at a class that no longer exists.
 */
class NoRetiredPlayerModelNamesRule :
    FunSpec({
        val retired =
            listOf(
                "NowPlayingViewModel",
                "NowPlayingState",
                "NowPlayingScreenState",
                "TransportState",
                "PlaybackErrorUiState",
            )

        test("no production declaration or reference uses a retired player model name") {
            val files = productionScope().files
            files.shouldNotBeEmpty()
            val offenders =
                files.flatMap { file ->
                    retired
                        .filter { name -> Regex("""\b$name\b""").containsMatchIn(file.text) }
                        .map { "${file.path} -> $it" }
                }
            withClue(offenders.joinToString(separator = "\n")) { offenders.shouldBeEmpty() }
        }
    })
