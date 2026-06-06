package com.calypsan.listenup.api.dto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ContributorRoleTest :
    FunSpec({
        test("fromApiValue resolves canonical tokens case-insensitively") {
            ContributorRole.fromApiValue("introduction") shouldBe ContributorRole.INTRODUCTION
            ContributorRole.fromApiValue("NARRATOR") shouldBe ContributorRole.NARRATOR
        }

        test("fromApiValue returns null for an unknown token") {
            ContributorRole.fromApiValue("special guest").shouldBeNull()
        }

        test("apiValue round-trips for every role") {
            ContributorRole.entries.forEach { role ->
                ContributorRole.fromApiValue(role.apiValue) shouldBe role
            }
        }
    })
