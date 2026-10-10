package com.calypsan.listenup.server.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The one slug algorithm behind tags, moods and genres; only the length limit and the errors differ. */
class SlugifyTest :
    FunSpec({
        val limit = 64

        listOf(
            "Sword & Sorcery" to SlugOutcome.Ok("sword-and-sorcery"),
            "  Hard SF!! " to SlugOutcome.Ok("hard-sf"),
            "Café Noir" to SlugOutcome.Ok("cafe-noir"),
            "!!!" to SlugOutcome.Empty,
            "" to SlugOutcome.Empty,
            "a".repeat(65) to SlugOutcome.TooLong,
            "a".repeat(64) to SlugOutcome.Ok("a".repeat(64)),
        ).forEach { (raw, expected) ->
            test("slugify of '${raw.take(20)}' (${raw.length} chars) at limit $limit is $expected".take(120)) {
                slugify(raw, limit) shouldBe expected
            }
        }

        test("a 65-character slug is accepted under a limit of 100") {
            slugify("a".repeat(65), maxLength = 100) shouldBe SlugOutcome.Ok("a".repeat(65))
        }
    })
