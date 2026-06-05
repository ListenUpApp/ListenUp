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
        test("BookReader survives a contractJson round-trip") {
            val v = BookReader(userId = "u2", displayName = "Bob", avatarType = "image", startedAtMs = 9L)
            contractJson.decodeFromString(BookReader.serializer(), contractJson.encodeToString(BookReader.serializer(), v)) shouldBe v
        }
    })
