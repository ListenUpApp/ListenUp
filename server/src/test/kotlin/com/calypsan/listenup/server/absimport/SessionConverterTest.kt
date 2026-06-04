package com.calypsan.listenup.server.absimport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class SessionConverterTest :
    FunSpec({
        val converter = SessionConverter()

        test("stable id, timeListening-derived endedAt, mapped fields") {
            val session =
                AbsSession(
                    id = "sess-9",
                    userId = "u-abs",
                    itemId = "book-1",
                    startPositionSeconds = 100.0,
                    endPositionSeconds = 460.0,
                    timeListeningSeconds = 3600.0,
                    startedAtMs = 1_730_000_000_000L,
                    playbackSpeed = 1.5f,
                    deviceLabel = "Pixel",
                )
            val event = converter.toEvent(session, bookId = "lu-book-1")

            event.id shouldBe "abs:sess-9" // stable id
            event.bookId shouldBe "lu-book-1"
            event.startedAt shouldBe 1_730_000_000_000L
            event.endedAt shouldBe 1_730_000_000_000L + 3_600_000L // startedAt + timeListening*1000
            event.startPositionMs shouldBe 100_000L
            event.endPositionMs shouldBe 460_000L
            event.playbackSpeed shouldBe 1.5f
            event.deviceLabel shouldBe "Pixel"
            event.deletedAt.shouldBeNull()
        }

        test("missing device falls back to Audiobookshelf label and endedAt never precedes startedAt") {
            val session =
                AbsSession(
                    id = "s",
                    userId = "u",
                    itemId = "b",
                    startPositionSeconds = 0.0,
                    endPositionSeconds = 0.0,
                    timeListeningSeconds = 0.0,
                    startedAtMs = 5_000L,
                    playbackSpeed = 1.0f,
                    deviceLabel = null,
                )
            val event = converter.toEvent(session, bookId = "lu-b")
            event.deviceLabel shouldBe "Audiobookshelf"
            event.endedAt shouldBe 5_000L // 0 timeListening → endedAt == startedAt
        }
    })
