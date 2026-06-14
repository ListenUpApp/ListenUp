package com.calypsan.listenup.api.dto.social

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SocialDtosTest :
    FunSpec({
        test("CurrentlyListeningSession survives a contractJson round-trip") {
            val v = CurrentlyListeningSession(userId = "u1", displayName = "Alice", avatarType = "auto", bookId = "b1", startedAtMs = 123L)
            contractJson.decodeFromString(CurrentlyListeningSession.serializer(), contractJson.encodeToString(CurrentlyListeningSession.serializer(), v)) shouldBe v
        }
        test("BookReadership round-trips") {
            val original =
                BookReadership(
                    readers =
                        listOf(
                            BookReaderEntry("u1", "Jake", "auto", currentProgressPct = 43, finishes = emptyList()),
                            BookReaderEntry(
                                "u2",
                                "You",
                                "image",
                                currentProgressPct = null,
                                finishes = listOf(1_777_000_000_000L, 1_610_000_000_000L),
                            ),
                        ),
                )
            contractJson.decodeFromString<BookReadership>(contractJson.encodeToString(original)) shouldBe original
        }
    })
