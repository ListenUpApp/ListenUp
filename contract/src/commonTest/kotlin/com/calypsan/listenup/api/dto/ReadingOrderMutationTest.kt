package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReadingOrderMutationTest :
    FunSpec({
        test("every order mutation round-trips through the outbox encoding") {
            listOf(
                ReadingOrderMutation.Create("cosmere", "URO"),
                ReadingOrderMutation.Rename("URO 2"),
                ReadingOrderMutation.Delete,
            ).forEach {
                contractJson.decodeFromString<ReadingOrderMutation>(contractJson.encodeToString<ReadingOrderMutation>(it)) shouldBe it
            }
        }

        test("every membership mutation round-trips") {
            listOf(
                ReadingOrderBookMutation.Add("m1", "ro", "b1"),
                ReadingOrderBookMutation.Remove("ro", "b1"),
                ReadingOrderBookMutation.Reorder("ro", listOf("b2", "b1")),
            ).forEach { mutation ->
                contractJson.decodeFromString<ReadingOrderBookMutation>(
                    contractJson.encodeToString<ReadingOrderBookMutation>(mutation),
                ) shouldBe mutation
            }
        }

        test("every follow mutation round-trips, and an unknown kind coerces to SERIES") {
            listOf(
                ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.ORDER, "ro"),
                ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.PUBLICATION),
                ReadingOrderFollowMutation.Clear("mistborn"),
            ).forEach { mutation ->
                contractJson.decodeFromString<ReadingOrderFollowMutation>(
                    contractJson.encodeToString<ReadingOrderFollowMutation>(mutation),
                ) shouldBe mutation
            }
            contractJson.decodeFromString<ReadingOrderFollowMutation>(
                """{"type":"ReadingOrderFollowMutation.Choose","seriesId":"s","kind":"CHRONOLOGICAL"}""",
            ) shouldBe ReadingOrderFollowMutation.Choose("s", ReadingOrderChoiceKind.SERIES)
        }
    })
