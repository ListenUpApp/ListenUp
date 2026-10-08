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
            ).forEach {
                contractJson.decodeFromString<ReadingOrderBookMutation>(
                    contractJson.encodeToString<ReadingOrderBookMutation>(it),
                ) shouldBe it
            }
        }

        test("every follow mutation round-trips, and an unknown kind coerces to SERIES") {
            listOf(
                ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.ORDER, "ro"),
                ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.PUBLICATION),
                ReadingOrderFollowMutation.Clear("mistborn"),
            ).forEach {
                contractJson.decodeFromString<ReadingOrderFollowMutation>(
                    contractJson.encodeToString<ReadingOrderFollowMutation>(it),
                ) shouldBe it
            }
            contractJson.decodeFromString<ReadingOrderFollowMutation>(
                """{"type":"ReadingOrderFollowMutation.Choose","seriesId":"s","kind":"CHRONOLOGICAL"}""",
            ) shouldBe ReadingOrderFollowMutation.Choose("s", ReadingOrderChoiceKind.SERIES)
        }
    })
