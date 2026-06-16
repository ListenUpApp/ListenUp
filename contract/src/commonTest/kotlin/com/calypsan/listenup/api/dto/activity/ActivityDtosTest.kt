package com.calypsan.listenup.api.dto.activity

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActivityDtosTest :
    FunSpec({
        test("ActivityEvent round-trips through contractJson") {
            val v =
                ActivityEvent(
                    id = "a1",
                    userId = "u1",
                    displayName = "Alice",
                    avatarType = "auto",
                    type = "finished_book",
                    occurredAtMs = 100L,
                    bookId = "b1",
                    isReread = false,
                    durationMs = 0L,
                    milestoneValue = 0,
                    milestoneUnit = null,
                    shelfId = null,
                    shelfName = null,
                )
            contractJson.decodeFromString(ActivityEvent.serializer(), contractJson.encodeToString(ActivityEvent.serializer(), v)) shouldBe v
        }
    })
