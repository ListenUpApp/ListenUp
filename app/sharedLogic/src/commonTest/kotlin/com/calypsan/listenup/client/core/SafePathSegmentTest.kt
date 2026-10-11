package com.calypsan.listenup.client.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class SafePathSegmentTest :
    FunSpec({
        listOf(
            "3f2a9c1e-7b64-4d0e-9a55-0c1f2b3d4e5f" to "3f2a9c1e-7b64-4d0e-9a55-0c1f2b3d4e5f",
            "01 - Chapter One.mp3" to "01 - Chapter One.mp3",
            ".." to "_",
            "." to "_",
            "" to "_",
            "../../etc/passwd" to ".._.._etc_passwd",
            "a\\b" to "a_b",
            "a\u0000b" to "a_b",
            "Disc 1/track.mp3" to "Disc 1_track.mp3",
        ).forEach { (raw, expected) ->
            test("safePathSegment(${raw.replace("\u0000", "<NUL>")}) is $expected") {
                safePathSegment(raw) shouldBe expected
            }
        }

        test("for any string the result is one plain segment, and cleaning it again changes nothing") {
            // Random strings almost never produce the dot references, so they are offered explicitly.
            checkAll(Arb.choice(Arb.string(), Arb.of("", ".", ".."))) { raw ->
                val cleaned = safePathSegment(raw)

                cleaned shouldNotContain "/"
                cleaned shouldNotContain "\\"
                cleaned shouldNotContain "\u0000"
                (cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") shouldBe true
                safePathSegment(cleaned) shouldBe cleaned
            }
        }
    })
