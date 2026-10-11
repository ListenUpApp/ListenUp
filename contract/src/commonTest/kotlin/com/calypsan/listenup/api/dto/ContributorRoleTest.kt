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

        test("apiValue round-trips for every role a person can hold") {
            ContributorRole.entries.filter { it.isAssignable }.forEach { role ->
                ContributorRole.fromApiValue(role.apiValue) shouldBe role
            }
        }

        test("UNKNOWN is a decode fallback, not a role: it is the only unassignable entry and no token resolves to it") {
            ContributorRole.entries.filterNot { it.isAssignable } shouldBe listOf(ContributorRole.UNKNOWN)
            ContributorRole.entries.last() shouldBe ContributorRole.UNKNOWN
            ContributorRole.fromApiValue(ContributorRole.UNKNOWN.apiValue).shouldBeNull()
            ContributorRole.fromApiValue("UNKNOWN").shouldBeNull()
        }
    })
