package com.calypsan.listenup.client.domain.version

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SemverTest :
    FunSpec({

        test("parses a plain major.minor.patch") {
            Semver.parseOrNull("0.6.0") shouldBe Semver(0, 6, 0)
        }

        test("strips a leading v") {
            Semver.parseOrNull("v1.2.0") shouldBe Semver(1, 2, 0)
        }

        test("ignores a pre-release suffix") {
            Semver.parseOrNull("1.2.3-beta") shouldBe Semver(1, 2, 3)
        }

        test("ignores build metadata") {
            Semver.parseOrNull("1.2.3+build") shouldBe Semver(1, 2, 3)
        }

        test("major differs between 0.6.0 and 1.2.0") {
            val client = Semver.parseOrNull("0.6.0")
            val server = Semver.parseOrNull("1.2.0")
            (client?.major != server?.major) shouldBe true
        }

        test("major is the same between 0.6.0 and 0.9.9") {
            val client = Semver.parseOrNull("0.6.0")
            val server = Semver.parseOrNull("0.9.9")
            (client?.major == server?.major) shouldBe true
        }

        test("malformed input parses to null") {
            Semver.parseOrNull("garbage") shouldBe null
        }

        test("empty input parses to null") {
            Semver.parseOrNull("") shouldBe null
        }
    })
