package com.calypsan.listenup.api.dto.social

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SocialDtosTest :
    FunSpec({
        test("a live CurrentlyListeningSession survives a contractJson round-trip") {
            val v =
                CurrentlyListeningSession(
                    userId = "u1",
                    displayName = "Alice",
                    avatarType = "auto",
                    bookId = "b1",
                    lastActiveAtMs = 123L,
                    isLive = true,
                )
            contractJson.decodeFromString<CurrentlyListeningSession>(contractJson.encodeToString(v)) shouldBe v
        }

        // The recent-listen fill is the half added after the section stopped hiding itself when
        // nobody was live; it crosses the same wire, so it round-trips in its own right.
        test("a recent-listen CurrentlyListeningSession survives a contractJson round-trip") {
            val v =
                CurrentlyListeningSession(
                    userId = "u2",
                    displayName = "Bob",
                    avatarType = "image",
                    bookId = "b2",
                    lastActiveAtMs = 1_777_000_000_000L,
                    isLive = false,
                )
            contractJson.decodeFromString<CurrentlyListeningSession>(contractJson.encodeToString(v)) shouldBe v
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
